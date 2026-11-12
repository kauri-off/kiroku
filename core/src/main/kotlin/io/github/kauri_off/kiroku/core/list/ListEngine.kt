package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

// The user's list over a store and MAL: the one place with the rules of a write and of a sync (section 7.3, N9).
// [unexpected] hears of the exceptions it did not expect: the store's, MAL's client's, and its own in background work.
class ListEngine(
    remote: RemoteList,
    store: LocalList,
    private val signedIn: StateFlow<Boolean>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val unexpected: (Throwable) -> Unit,
) : ListRepository {
    private sealed interface Request {
        val id: AnimeId
        val session: Int
    }

    private class Put(
        val anime: Anime,
        val values: ListValues?,
        val epoch: Int,
        override val session: Int,
    ) : Request {
        override val id: AnimeId get() = anime.id
    }

    private class Direct(
        val write: ListWrite,
        val result: CompletableDeferred<Outcome<Unit>>,
        override val session: Int,
    ) : Request {
        override val id: AnimeId get() = write.anime.id
    }

    // A store call that threw; it fails the one call the engine was asked in as a storage error.
    private class StoreFailure(
        cause: Throwable,
    ) : Exception(cause)

    // MAL's client throwing fails the one call: an IOException as no connection, signed out as expired, else reported.
    private val mal =
        object : RemoteList {
            override suspend fun fetch() = remoteCall { remote.fetch() }

            override suspend fun fetchEntry(id: AnimeId) = remoteCall { remote.fetchEntry(id) }

            override suspend fun put(
                anime: Anime,
                values: ListValues?,
            ) = remoteCall { remote.put(anime, values) }
        }

    private val local =
        object : LocalList {
            override fun observeEntries() = store.observeEntries().catch { throw if (it is Exception) storeFailure(it) else it }

            override suspend fun read(id: AnimeId) = storeCall { store.read(id) }

            override suspend fun readAll() = storeCall { store.readAll() }

            override suspend fun write(entries: Map<AnimeId, StoredEntry?>) = storeCall { store.write(entries) }
        }

    // Held for every change to the store and to the fields below it, never while MAL is asked.
    private val mutex = Mutex()

    // A failed request starts a new epoch of its title, which the requests still waiting from the old one are not in.
    private val epochs = mutableMapOf<AnimeId, Int>()

    // A sign-out starts a new session: nothing accepted in an earlier one is sent, and no answer to one is stored.
    private var session = 0

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

    // One lane: W1's order and N9's read before its write both rely on no other answer coming between.
    private val queue = Channel<Request>(Channel.UNLIMITED)
    private val failures =
        MutableSharedFlow<WriteFailure>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        check(scope.isActive) { STOPPED }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (request in queue) {
                    try {
                        handle(request)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        unexpected(e)
                        if (request is Direct) request.result.completeExceptionally(e)
                    }
                    mutex.withLock { accepted.remove(request) }
                }
            } finally {
                withContext(NonCancellable) { stop() }
            }
        }
        scope.launch { signedIn.collect { if (!it) mutex.withLock { signOut() } } }
    }

    private suspend fun handle(request: Request) {
        when (request) {
            is Put ->
                try {
                    send(request)
                } catch (_: StoreFailure) {
                    lost(request)
                }
            is Direct ->
                request.result.complete(
                    try {
                        send(request)
                    } catch (_: StoreFailure) {
                        Outcome.Failure(DataError.StorageError)
                    },
                )
        }
    }

    // A store that cannot be read is tried a few times, and then the flow ends with a storage error.
    override fun observeEntries(): Flow<List<ListEntry>> =
        combine(
            flow {
                mutex.withLock { restore() }
                emitAll(local.observeEntries())
            }.retryWhen { cause, attempt ->
                (cause is StoreFailure && attempt < STORE_ATTEMPTS - 1).also { if (it) delay(STORE_RETRY) }
            }.catch { throw if (it is StoreFailure) DataException(DataError.StorageError, it.cause) else it },
            signedIn,
        ) { entries, signedIn -> if (signedIn) entries else emptyList() }
            .distinctUntilChanged()

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
        return try {
            withContext(NonCancellable) {
                locked {
                    check(!stopped) { STOPPED }
                    try {
                        edit(write)
                    } catch (_: StoreFailure) {
                        failures.tryEmit(WriteFailure(DataError.StorageError, write))
                        null
                    }
                }
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException(STOPPED, e)
        }
    }

    // An unconfirmed title with nothing on its way to MAL is sent even unchanged, so a Retry always does something.
    private suspend fun edit(write: ListWrite): ListChange? {
        val id = write.anime.id
        val stored = local.read(id)
        val current = stored?.shown
        val values = write.applyTo(current, today())
        val owed = stored?.unconfirmed == true && id !in pending
        if (values == current?.values && !owed) return null
        val anime = current?.anime ?: write.anime
        val next = values?.let { ListEntry(anime, it, clock.instant()) }
        save(id, StoredEntry(next, stored?.confirmed, unconfirmed = true))
        pending[id] = (pending[id] ?: 0) + 1
        accept(Put(anime, values, epochs[id] ?: 0, session))
        return ListChange(current, next, write.steps)
    }

    override suspend fun send(write: ListWrite): Outcome<Unit> {
        if (!signedIn.value) return Outcome.Failure(DataError.SessionExpired)
        val result = CompletableDeferred<Outcome<Unit>>()
        locked { accept(Direct(write, result, session)) }
        return result.await()
    }

    private fun accept(request: Request) {
        check(!stopped) { STOPPED }
        accepted += request
        queue.trySend(request)
    }

    // W4: a title edited since the download began keeps its values; every other one becomes what MAL has.
    private suspend fun download(): Outcome<Unit> {
        val (since, began) = locked { changes to session }
        val fetched =
            when (val outcome = mal.fetch()) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value.associateBy { it.anime.id }
            }
        return locked {
            if (!signedIn.value || session != began) return@locked Outcome.Failure(DataError.SessionExpired)
            try {
                val stored = local.readAll()
                val synced =
                    (stored.keys + fetched.keys)
                        .filterNot { it in pending || (touched[it] ?: 0) > since }
                        .associateWith { fetched[it]?.let { StoredEntry.settled(it) } }
                        .filter { (id, entry) -> entry != stored[id] }
                if (synced.isNotEmpty()) local.write(synced)
                Outcome.Success(Unit)
            } catch (_: StoreFailure) {
                Outcome.Failure(DataError.StorageError)
            }
        }
    }

    // W1 and W2: one request at a time; a failure returns the title to the confirmed values and drops its waiting edits.
    private suspend fun send(put: Put) {
        val id = put.id
        if (locked { (epochs[id] ?: 0) != put.epoch || session != put.session }) return
        val outcome =
            if (signedIn.value) mal.put(put.anime, put.values) else Outcome.Failure(DataError.SessionExpired)
        val failure =
            locked {
                if (session != put.session) return@locked null
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
                        save(id, stored?.confirmed?.let { StoredEntry.settled(it) })
                        val shown = stored?.shown
                        WriteFailure(outcome.error, shown?.let { ListWrite.Save(it.anime, it.values) } ?: ListWrite.Remove(put.anime))
                    }
                }
            }
        if (failure != null && signedIn.value) failures.tryEmit(failure)
    }

    // A request the store failed under is a failed write (W2), and its Retry carries the title's last edit.
    private suspend fun lost(put: Put) {
        val id = put.id
        val last =
            mutex.withLock {
                if (session != put.session) return
                epochs[id] = put.epoch + 1
                pending.remove(id)
                try {
                    save(id, local.read(id)?.confirmed?.let { StoredEntry.settled(it) })
                } catch (_: StoreFailure) {
                }
                accepted.filterIsInstance<Put>().lastOrNull { it.id == id && it.epoch == put.epoch } ?: put
            }
        val retry = last.values?.let { ListWrite.Save(last.anime, it) } ?: ListWrite.Remove(last.anime)
        if (signedIn.value) failures.tryEmit(WriteFailure(DataError.StorageError, retry))
    }

    // N9: the write is applied to the entry as MAL has it now, and nothing is sent when that changes nothing.
    private suspend fun send(direct: Direct): Outcome<Unit> {
        val write = direct.write
        val id = direct.id
        if (!current(direct)) return Outcome.Failure(DataError.SessionExpired)
        val confirmed =
            when (val outcome = mal.fetchEntry(id)) {
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
                if (!current(direct)) return Outcome.Failure(DataError.SessionExpired)
                when (val outcome = mal.put(confirmed?.anime ?: write.anime, values)) {
                    is Outcome.Failure -> return outcome
                    is Outcome.Success -> outcome.value
                }
            }
        locked { if (session == direct.session) confirm(id, entry) }
        return Outcome.Success(Unit)
    }

    private suspend fun current(request: Request): Boolean = signedIn.value && locked { session == request.session }

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
        if (next != stored) save(id, next)
    }

    // W5: an edit whose request died with an earlier process returns to the confirmed values, and the list is synced.
    private suspend fun restore() {
        if (restored) return
        val returned =
            try {
                returnToConfirmed()
            } catch (_: StoreFailure) {
                return
            }
        restored = true
        if (returned) scope.launch { sync() }
    }

    // Nothing waiting is sent after a sign-out, and every unconfirmed title returns to its confirmed values.
    private suspend fun signOut() {
        session++
        pending.clear()
        try {
            returnToConfirmed()
        } catch (_: StoreFailure) {
        }
    }

    private suspend fun returnToConfirmed(): Boolean {
        val unconfirmed = local.readAll().filterValues { it.unconfirmed }
        if (unconfirmed.isEmpty()) return false
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
                try {
                    returnToConfirmed()
                } catch (_: StoreFailure) {
                } catch (_: CancellationException) {
                }
                accepted.filterIsInstance<Direct>().also { accepted.clear() }
            }
        directs.forEach { it.result.completeExceptionally(IllegalStateException(STOPPED)) }
    }

    private suspend inline fun <T> locked(block: () -> T): T =
        mutex.withLock {
            restore()
            block()
        }

    private suspend inline fun <T> remoteCall(call: () -> Outcome<T>): Outcome<T> =
        try {
            call()
        } catch (e: Exception) {
            ended(e)
            when {
                !signedIn.value -> Outcome.Failure(DataError.SessionExpired)
                e is IOException -> Outcome.Failure(DataError.NoConnection)
                else -> Outcome.Failure(DataError.ServerError).also { unexpected(e) }
            }
        }

    private suspend inline fun <T> storeCall(call: () -> T): T =
        try {
            call()
        } catch (e: Exception) {
            throw storeFailure(e)
        }

    private suspend fun storeFailure(e: Exception): Exception {
        ended(e)
        unexpected(e)
        return StoreFailure(e)
    }

    // A cancellation of the caller or of the engine's scope ends the call; any other is a failure like any exception.
    private suspend fun ended(e: Exception) {
        if (e !is CancellationException) return
        currentCoroutineContext().ensureActive()
        if (!scope.isActive) throw e
    }

    private suspend fun save(
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
        const val STORE_ATTEMPTS = 3
        val STORE_RETRY = 1.seconds
    }
}
