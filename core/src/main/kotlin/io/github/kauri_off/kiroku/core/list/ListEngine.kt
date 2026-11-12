package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.repository.ListChange
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.WriteFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate

// The user's list over a store and MAL: the one place with the rules of a write and of a sync (section 7.3, N9).
class ListEngine(
    private val remote: RemoteList,
    private val local: LocalList,
    private val signedIn: StateFlow<Boolean>,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : ListRepository {
    private sealed interface Request {
        val id: AnimeId
    }

    private class Put(
        val anime: Anime,
        val values: ListValues?,
        val epoch: Int,
    ) : Request {
        override val id: AnimeId get() = anime.id
    }

    private class Direct(
        val write: ListWrite,
        val result: CompletableDeferred<Outcome<Unit>>,
    ) : Request {
        override val id: AnimeId get() = write.anime.id
    }

    // Held for every change to the store and to the fields below it, never while MAL is asked.
    private val mutex = Mutex()

    // A failed request starts a new epoch of its title, which the requests still waiting from the old one are not in.
    private val epochs = mutableMapOf<AnimeId, Int>()

    // The requests of a title's current epoch that are not answered yet.
    private val pending = mutableMapOf<AnimeId, Int>()
    private val touched = mutableMapOf<AnimeId, Long>()
    private var changes = 0L
    private val accepted = mutableListOf<Request>()
    private var restored = false
    private var stopped = false

    private val syncs = Any()
    private var running: Deferred<Outcome<Unit>>? = null
    private var following: Deferred<Outcome<Unit>>? = null

    private val queue = Channel<Request>(Channel.UNLIMITED)
    private val failures =
        MutableSharedFlow<WriteFailure>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        check(scope.isActive) { STOPPED }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (request in queue) {
                    when (request) {
                        is Put -> send(request)
                        is Direct -> request.result.complete(send(request))
                    }
                    mutex.withLock { accepted.remove(request) }
                }
            } finally {
                withContext(NonCancellable) { stop() }
            }
        }
    }

    override fun observeEntries(): Flow<List<ListEntry>> =
        combine(
            flow {
                mutex.withLock { restore() }
                emitAll(local.observeEntries())
            },
            signedIn,
        ) { entries, signedIn -> if (signedIn) entries else emptyList() }

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> =
        observeEntries().map { entries -> entries.firstOrNull { it.anime.id == id } }.distinctUntilChanged()

    override fun observeWriteFailures(): Flow<WriteFailure> = failures.asSharedFlow()

    // W6: one download at a time; calls made while one runs share the one that follows it.
    override suspend fun sync(): Outcome<Unit> {
        if (!signedIn.value) return Outcome.Failure(DataError.SessionExpired)
        val sync =
            synchronized(syncs) {
                following ?: run {
                    val previous = running
                    val started =
                        scope.async(start = CoroutineStart.LAZY) {
                            previous?.join()
                            download()
                        }
                    if (previous == null) running = started else following = started
                    started.invokeOnCompletion {
                        synchronized(syncs) {
                            if (running === started) running = following.also { following = null }
                            if (following === started) following = null
                        }
                    }
                    started.start()
                    started
                }
            }
        return try {
            sync.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException(STOPPED, e)
        }
    }

    override suspend fun write(write: ListWrite): ListChange? {
        if (!signedIn.value) {
            failures.tryEmit(WriteFailure(DataError.SessionExpired, write))
            return null
        }
        return withContext(NonCancellable) {
            locked {
                check(!stopped) { STOPPED }
                val id = write.anime.id
                val stored = local.read(id)
                val current = stored?.shown
                val values = write.applyTo(current, today())
                if (values == current?.values) return@locked null
                val anime = current?.anime ?: write.anime
                val next = values?.let { ListEntry(anime, it, clock.instant()) }
                store(id, StoredEntry(next, stored?.confirmed, unconfirmed = true))
                pending[id] = (pending[id] ?: 0) + 1
                accept(Put(anime, values, epochs[id] ?: 0))
                ListChange(current, next, write.steps)
            }
        }
    }

    override suspend fun send(write: ListWrite): Outcome<Unit> {
        if (!signedIn.value) return Outcome.Failure(DataError.SessionExpired)
        val result = CompletableDeferred<Outcome<Unit>>()
        locked { accept(Direct(write, result)) }
        return result.await()
    }

    private fun accept(request: Request) {
        check(!stopped) { STOPPED }
        accepted += request
        queue.trySend(request)
    }

    // W4: a title edited since the download began keeps its values; every other one becomes what MAL has.
    private suspend fun download(): Outcome<Unit> {
        val since = locked { changes }
        val fetched =
            when (val outcome = remote.fetch()) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value.associateBy { it.anime.id }
            }
        return locked {
            if (!signedIn.value) return@locked Outcome.Failure(DataError.SessionExpired)
            val stored = local.readAll()
            val synced =
                (stored.keys + fetched.keys)
                    .filterNot { it in pending || (touched[it] ?: 0) > since }
                    .associateWith { fetched[it]?.let { StoredEntry.settled(it) } }
                    .filter { (id, entry) -> entry != stored[id] }
            if (synced.isNotEmpty()) local.write(synced)
            Outcome.Success(Unit)
        }
    }

    // W1 and W2: one request at a time; a failure returns the title to the confirmed values and drops its waiting edits.
    private suspend fun send(put: Put) {
        val id = put.id
        if (locked { epochs[id] ?: 0 } != put.epoch) return
        val outcome =
            if (signedIn.value) remote.put(put.anime, put.values) else Outcome.Failure(DataError.SessionExpired)
        val failure =
            locked {
                when (outcome) {
                    is Outcome.Success -> {
                        val waiting = (pending[id] ?: 1) - 1
                        if (waiting > 0) pending[id] = waiting else pending.remove(id)
                        confirm(id, outcome.value)
                        null
                    }
                    is Outcome.Failure -> {
                        epochs[id] = put.epoch + 1
                        pending.remove(id)
                        val stored = local.read(id)
                        store(id, stored?.confirmed?.let { StoredEntry.settled(it) })
                        val shown = stored?.shown
                        WriteFailure(outcome.error, shown?.let { ListWrite.Save(it.anime, it.values) } ?: ListWrite.Remove(put.anime))
                    }
                }
            }
        if (failure != null && signedIn.value) failures.tryEmit(failure)
    }

    // N9: the write is applied to the entry as MAL has it now, and nothing is sent when that changes nothing.
    private suspend fun send(direct: Direct): Outcome<Unit> {
        val write = direct.write
        val id = direct.id
        if (!signedIn.value) return Outcome.Failure(DataError.SessionExpired)
        val confirmed =
            when (val outcome = remote.fetchEntry(id)) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value
            }
        val values = write.applyTo(confirmed, today())
        val refusal =
            when {
                confirmed == null && write !is ListWrite.Save && write !is ListWrite.Remove -> DataError.NotFound
                write is ListWrite.MarkWatched && confirmed != null && confirmed.values.watchedEpisodes < write.episode - 1 ->
                    DataError.InvalidRequest
                else -> null
            }
        if (refusal != null) return Outcome.Failure(refusal)
        val entry =
            if (values == confirmed?.values) {
                confirmed
            } else {
                when (val outcome = remote.put(confirmed?.anime ?: write.anime, values)) {
                    is Outcome.Failure -> return outcome
                    is Outcome.Success -> outcome.value
                }
            }
        locked { confirm(id, entry) }
        return Outcome.Success(Unit)
    }

    // MAL's answer is the confirmed entry, and the shown one when no edit is waiting and its values differ from it.
    private suspend fun confirm(
        id: AnimeId,
        entry: ListEntry?,
    ) {
        val stored = local.read(id)
        val shown = stored?.shown
        val next =
            when {
                id in pending -> StoredEntry(shown, entry, unconfirmed = true)
                entry == null -> null
                shown?.values == entry.values -> StoredEntry.settled(shown)
                else -> StoredEntry.settled(entry)
            }
        if (next == stored) touch(id) else store(id, next)
    }

    // W5: an edit whose request died with an earlier process returns to the confirmed values, and the list is synced.
    private suspend fun restore() {
        if (restored) return
        restored = true
        if (returnToConfirmed()) scope.launch { sync() }
    }

    private suspend fun returnToConfirmed(): Boolean {
        val unconfirmed = local.readAll().filterValues { it.unconfirmed }
        if (unconfirmed.isEmpty()) return false
        unconfirmed.keys.forEach(::touch)
        local.write(unconfirmed.mapValues { (_, stored) -> stored.confirmed?.let { StoredEntry.settled(it) } })
        return true
    }

    private suspend fun stop() {
        val directs =
            mutex.withLock {
                stopped = true
                restored = true
                queue.close()
                pending.clear()
                returnToConfirmed()
                accepted.filterIsInstance<Direct>().also { accepted.clear() }
            }
        directs.forEach { it.result.completeExceptionally(IllegalStateException(STOPPED)) }
    }

    private suspend inline fun <T> locked(block: () -> T): T =
        mutex.withLock {
            restore()
            block()
        }

    private suspend fun store(
        id: AnimeId,
        entry: StoredEntry?,
    ) {
        touch(id)
        local.write(mapOf(id to entry))
    }

    private fun touch(id: AnimeId) {
        touched[id] = ++changes
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private companion object {
        const val STOPPED = "The list's scope has ended"
    }
}
