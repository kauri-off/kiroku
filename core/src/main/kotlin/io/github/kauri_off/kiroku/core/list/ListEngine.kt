package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.ListChange
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.WriteFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

// The user's list over a store and MAL: the one place with the rules of a write and of a sync (section 7.3, N9).
// [sessions] gives each collector the session as it is and then every change, and nothing before it is known; until
// then the engine reads nothing, sends nothing and answers no call.
// [unexpected] hears of the exceptions it did not expect: the store's, MAL's client's, and its own in background work.
class ListEngine(
    remote: RemoteList,
    store: LocalList,
    private val sessions: Flow<Session>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val unexpected: (Throwable) -> Unit,
) : ListRepository {
    private sealed interface Request {
        val id: AnimeId
        val list: Int
    }

    private class Put(
        val anime: Anime,
        val values: ListValues?,
        val epoch: Int,
        override val list: Int,
    ) : Request {
        override val id: AnimeId get() = anime.id
    }

    private class Direct(
        val write: ListWrite,
        val result: CompletableDeferred<Outcome<Unit>>,
        override val list: Int,
    ) : Request {
        override val id: AnimeId get() = write.anime.id
    }

    // A store call that threw; it fails the one call the engine was asked in as a storage error.
    private class StoreFailure(
        cause: Throwable,
    ) : Exception(cause)

    // MAL's client throwing fails the one call: an IOException as no connection, not signed in as expired, else
    // reported. An answer about another title is the engine's own exception, and none of it is stored.
    private val mal =
        object : RemoteList {
            override suspend fun fetch() = remoteCall { remote.fetch() }

            override suspend fun fetchEntry(id: AnimeId) = remoteCall { remote.fetchEntry(id) }.about(id)

            override suspend fun put(
                anime: Anime,
                values: ListValues?,
            ) = remoteCall { remote.put(anime, values) }.about(anime.id)

            private fun Outcome<ListEntry?>.about(id: AnimeId) =
                also { check(it !is Outcome.Success || it.value == null || it.value.anime.id == id) { WRONG_TITLE } }
        }

    private val local =
        object : LocalList {
            override fun observeEntries() = store.observeEntries().storeFlow()

            override fun observeEntry(id: AnimeId) = store.observeEntry(id).storeFlow()

            override suspend fun state() = storeCall { store.state() }

            override suspend fun read(id: AnimeId) = storeCall { store.read(id) }

            override suspend fun readAll() = storeCall { store.readAll() }

            override suspend fun write(entries: Map<AnimeId, StoredEntry?>) = storeCall { store.write(entries) }

            override suspend fun add(
                anime: Anime,
                entry: StoredEntry,
            ) = storeCall { store.add(anime, entry) }

            override suspend fun download(
                titles: List<Anime>,
                entries: Map<AnimeId, StoredEntry?>,
                at: Instant,
            ) = storeCall { store.download(titles, entries, at) }

            override suspend fun restore(entries: Map<AnimeId, StoredEntry?>) = storeCall { store.restore(entries) }

            override suspend fun clear(owner: SignInId?) = storeCall { store.clear(owner) }

            private fun <T> Flow<T>.storeFlow() = catch { throw if (it is Exception) storeFailure(it) else it }
        }

    // Held for every change to the store and to the fields below it, never while MAL is asked.
    private val mutex = Mutex()

    // A failed request starts a new epoch of its title, which the requests still waiting from the old one are not in.
    private val epochs = mutableMapOf<AnimeId, Int>()

    // A sign-out and a new sign-in start a new list: nothing accepted for an earlier one is sent, and no answer to
    // one is stored.
    private var list = 0

    // The session the store was last made to agree with.
    private var settled: Session? = null

    // The store's time of the last download, which only the engine changes.
    private val syncedAt = MutableStateFlow<Instant?>(null)

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
                        when (request) {
                            is Put -> lost(request, DataError.ServerError)
                            is Direct -> request.result.completeExceptionally(e)
                        }
                    }
                    mutex.withLock { accepted.remove(request) }
                }
            } finally {
                withContext(NonCancellable) { stop() }
            }
        }
        scope.launch { sessions.collect { follow() } }
    }

    // S9 and a new sign-in do not wait for the list's next use; a store that fails now is asked again then.
    private suspend fun follow() {
        try {
            mutex.withLock { settle() }
        } catch (_: StoreFailure) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unexpected(e)
        }
    }

    private suspend fun handle(request: Request) {
        when (request) {
            is Put ->
                try {
                    send(request)
                } catch (_: StoreFailure) {
                    lost(request, DataError.StorageError)
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

    override fun observeEntries(): Flow<List<ListEntry>> = shown(emptyList()) { local.observeEntries() }

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> = shown(null) { local.observeEntry(id) }

    override fun observeSyncedAt(): Flow<Instant?> = shown(null) { syncedAt }

    // Signed out there is nothing to show; otherwise what the store has, once it holds the list of this session. A
    // store that cannot be read is tried a few times in a row, and then the flow ends with a storage error.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> shown(
        nothing: T,
        stored: () -> Flow<T>,
    ): Flow<T> =
        sessions
            .distinctUntilChanged()
            .flatMapLatest { session ->
                if (session == Session.SignedOut) return@flatMapLatest flowOf(nothing)
                flow {
                    var failed = 0
                    emitAll(
                        flow {
                            mutex.withLock { settle() }
                            emitAll(stored())
                        }.onEach { failed = 0 }
                            .retryWhen { cause, _ ->
                                (cause is StoreFailure && ++failed < STORE_ATTEMPTS).also { if (it) delay(STORE_RETRY) }
                            }.catch { throw if (it is StoreFailure) DataException(DataError.StorageError, it.cause) else it },
                    )
                }
            }.distinctUntilChanged()

    override fun observeWriteFailures(): Flow<WriteFailure> = failures.asSharedFlow()

    // W6: one download at a time; calls made while one runs share the one that follows it.
    override suspend fun sync(): Outcome<Unit> {
        if (!signedIn()) return Outcome.Failure(DataError.SessionExpired)
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

    // S6, W2: without a session that is signed in the write changes nothing, and its failure offers no Retry.
    override suspend fun write(write: ListWrite): ListChange? {
        val refused = WriteFailure(DataError.SessionExpired, write)
        if (!signedIn()) {
            failures.tryEmit(refused)
            return null
        }
        return try {
            withContext(NonCancellable) {
                try {
                    locked {
                        check(!stopped) { STOPPED }
                        if (settled is Session.SignedIn) {
                            edit(write)
                        } else {
                            failures.tryEmit(refused)
                            null
                        }
                    }
                } catch (_: StoreFailure) {
                    failures.tryEmit(WriteFailure(DataError.StorageError, write))
                    null
                }
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException(STOPPED, e)
        }
    }

    // An unconfirmed title with nothing on its way to MAL is sent even unchanged, so a Retry always does something.
    // The write's own copy of the title's data counts only for a title the store does not know.
    private suspend fun edit(write: ListWrite): ListChange? {
        val id = write.anime.id
        val stored = local.read(id)
        val anime = stored?.anime ?: write.anime
        val current = stored?.entry?.shown?.let { ListEntry(anime, it.values, it.updatedAt) }
        val values = write.applyTo(current, today())
        val owed = stored?.entry?.unconfirmed == true && id !in pending
        if (values == current?.values && !owed) return null
        val next = values?.let { ListEntry(anime, it, clock.instant()) }
        val entry = StoredEntry(next?.record, stored?.entry?.confirmed, unconfirmed = true)
        touch(id)
        if (stored == null) local.add(anime, entry) else local.write(mapOf(id to entry))
        pending[id] = (pending[id] ?: 0) + 1
        accept(Put(anime, values, epochs[id] ?: 0, list))
        return ListChange(current, next, write.steps)
    }

    override suspend fun send(write: ListWrite): Outcome<Unit> {
        val expired = Outcome.Failure(DataError.SessionExpired)
        if (!signedIn()) return expired
        val result = CompletableDeferred<Outcome<Unit>>()
        val refused =
            try {
                locked { if (settled is Session.SignedIn) null.also { accept(Direct(write, result, list)) } else expired }
            } catch (_: StoreFailure) {
                Outcome.Failure(DataError.StorageError)
            }
        return refused ?: result.await()
    }

    private fun accept(request: Request) {
        check(!stopped) { STOPPED }
        accepted += request
        queue.trySend(request)
    }

    // W4: a title edited since the download began keeps its values; every other one becomes what MAL has. Entries
    // are compared without their titles, so the store is handed those alone whose values or time MAL has changed, and
    // the data of every title on the list, whatever became of its entry.
    private suspend fun download(): Outcome<Unit> =
        try {
            val (since, began) = locked { changes to list }
            when (val outcome = mal.fetch()) {
                is Outcome.Failure -> outcome
                is Outcome.Success -> {
                    val fetched = outcome.value.associateBy { it.anime.id }
                    locked {
                        if (settled !is Session.SignedIn || list != began) return@locked Outcome.Failure(DataError.SessionExpired)
                        val stored = local.readAll()
                        val changed =
                            (stored.keys + fetched.keys)
                                .filterNot { it in pending || (touched[it] ?: 0) > since }
                                .associateWith { fetched[it]?.let { StoredEntry.settled(it.record) } }
                                .filter { (id, entry) -> entry != stored[id] }
                        val now = clock.instant()
                        local.download(fetched.values.map { it.anime }, changed, now)
                        syncedAt.value = now
                        Outcome.Success(Unit)
                    }
                }
            }
        } catch (_: StoreFailure) {
            Outcome.Failure(DataError.StorageError)
        }

    // W1 and W2: one request at a time; a failure returns the title to the confirmed values and drops its waiting edits.
    private suspend fun send(put: Put) {
        val id = put.id
        if (locked { (epochs[id] ?: 0) != put.epoch || list != put.list }) return
        val outcome = if (signedIn()) mal.put(put.anime, put.values) else Outcome.Failure(DataError.SessionExpired)
        val failure =
            locked {
                if (list != put.list) return@locked null
                when (outcome) {
                    is Outcome.Success -> {
                        val waiting = (pending[id] ?: 1) - 1
                        if (waiting > 0) pending[id] = waiting else pending.remove(id)
                        confirm(put.anime, outcome.value?.record)
                        null
                    }
                    is Outcome.Failure -> {
                        epochs[id] = put.epoch + 1
                        pending.remove(id)
                        val stored = local.read(id)?.entry
                        save(id, stored?.confirmed?.let { StoredEntry.settled(it) })
                        val shown = stored?.shown
                        WriteFailure(outcome.error, shown?.let { ListWrite.Save(put.anime, it.values) } ?: ListWrite.Remove(put.anime))
                    }
                }
            }
        if (failure != null) failures.tryEmit(failure)
    }

    // A request the store or the engine itself failed under is a failed write (W2), its Retry carries the title's
    // last edit, and the title is in the next sync (W5).
    private suspend fun lost(
        put: Put,
        error: DataError,
    ) {
        val id = put.id
        val last =
            mutex.withLock {
                if (list != put.list) return
                epochs[id] = put.epoch + 1
                pending.remove(id)
                try {
                    save(id, local.read(id)?.entry?.confirmed?.let { StoredEntry.settled(it) })
                } catch (_: StoreFailure) {
                }
                accepted.filterIsInstance<Put>().lastOrNull { it.id == id && it.epoch == put.epoch } ?: put
            }
        val retry = last.values?.let { ListWrite.Save(last.anime, it) } ?: ListWrite.Remove(last.anime)
        failures.tryEmit(WriteFailure(error, retry))
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
        locked { if (list == direct.list) confirm(entry?.anime ?: write.anime, entry?.record) }
        return Outcome.Success(Unit)
    }

    private suspend fun current(request: Request): Boolean = locked { settled is Session.SignedIn && list == request.list }

    // MAL's answer is the confirmed entry, and the shown one when no edit is waiting and its values differ from it.
    // Of the answer's title data nothing is stored, unless the store does not know the title.
    private suspend fun confirm(
        anime: Anime,
        answer: ListRecord?,
    ) {
        val id = anime.id
        val title = local.read(id)
        val stored = title?.entry
        val shown = stored?.shown
        val next =
            when {
                id in pending -> StoredEntry(shown, answer, unconfirmed = true)
                answer == null -> null
                shown?.values == answer.values -> StoredEntry.settled(shown)
                else -> StoredEntry.settled(answer)
            }
        if (next == stored) return
        touch(id)
        if (title == null && next != null) local.add(anime, next) else local.write(mapOf(id to next))
    }

    // Makes the store agree with the session: signed out it holds nothing (S9), a sign-in other than the list's owner
    // starts from nothing, and the same sign-in or an expired session keeps what is stored (S6).
    private suspend fun settle() {
        val session = sessions.first()
        if (session == settled) return
        val state = if (session == Session.SignedOut) null else local.state()
        if (state == null || (session is Session.SignedIn && state.owner != session.signIn)) {
            forget((session as? Session.SignedIn)?.signIn)
        } else {
            syncedAt.value = state.syncedAt
            restore()
        }
        settled = session
    }

    private suspend fun forget(owner: SignInId?) {
        list++
        pending.clear()
        epochs.clear()
        touched.clear()
        syncedAt.value = null
        local.clear(owner)
        restored = true
    }

    // W5: an edit whose request died with an earlier process returns to the confirmed values, and the list is synced.
    private suspend fun restore() {
        if (restored) return
        val returned = returnToConfirmed()
        restored = true
        if (returned) scope.launch { sync() }
    }

    // The requests of these titles were cut off, so what MAL has of them is not known: a sync is owed.
    private suspend fun returnToConfirmed(): Boolean {
        val unconfirmed = local.readAll().filterValues { it.unconfirmed }
        if (unconfirmed.isEmpty()) return false
        local.restore(unconfirmed.mapValues { (_, stored) -> stored.confirmed?.let { StoredEntry.settled(it) } })
        syncedAt.value = null
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
                    if (settled != null) returnToConfirmed()
                } catch (_: StoreFailure) {
                } catch (_: CancellationException) {
                }
                accepted.filterIsInstance<Direct>().also { accepted.clear() }
            }
        directs.forEach { it.result.completeExceptionally(IllegalStateException(STOPPED)) }
    }

    private suspend fun signedIn(): Boolean = sessions.first() is Session.SignedIn

    private suspend inline fun <T> locked(block: () -> T): T =
        mutex.withLock {
            settle()
            block()
        }

    private suspend inline fun <T> remoteCall(call: () -> Outcome<T>): Outcome<T> =
        try {
            call()
        } catch (e: Exception) {
            ended(e)
            when {
                !signedIn() -> Outcome.Failure(DataError.SessionExpired)
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

    // Values alone.
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
        const val WRONG_TITLE = "MAL's client answered about another title"
        const val STORE_ATTEMPTS = 3
        val STORE_RETRY = 1.seconds
    }
}
