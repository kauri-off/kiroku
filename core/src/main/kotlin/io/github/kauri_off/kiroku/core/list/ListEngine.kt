package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.error.StoreCalls
import io.github.kauri_off.kiroku.core.error.StoreFailure
import io.github.kauri_off.kiroku.core.error.retriedOverStore
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListField
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.changedFrom
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.ListChange
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.Untouched
import io.github.kauri_off.kiroku.core.repository.WriteFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

// The user's list over a store and MAL: the one place with the rules of a write and of a sync (section 7.3, N9).
class ListEngine(
    remote: RemoteList,
    store: LocalList,
    // Replays the session to each collector; before it gives one, nothing is read, sent or answered.
    private val sessions: Flow<Session>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    // How many titles a download missed MAL is asked about in one sync.
    private val checksPerSync: Int = CHECKS_PER_SYNC,
    // Where a sync's checks start among the titles never left unanswered, so a restart that forgot them moves on.
    private val random: Random = Random.Default,
    // Hears of what nobody expected: MAL's client's and the engine's own exceptions, and the first of a store's streak.
    private val unexpected: (Throwable) -> Unit,
) : ListRepository {
    private sealed interface Request {
        val id: AnimeId
        val list: Int
    }

    // [from]: MAL's confirmed values with the waiting edits applied, as the edit found them; [named]: what it sets, null all.
    private class Put(
        val anime: Anime,
        val values: ListValues?,
        val from: ListValues?,
        val named: Set<ListField>?,
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

        // Its write went out to MAL, which may have made it whatever follows.
        @Volatile
        var made = false
    }

    // MAL's client throwing fails the one call: an IOException as no connection, not signed in as expired, else reported.
    private val mal =
        object : RemoteList {
            override suspend fun fetch() = remoteCall { remote.fetch() }

            override suspend fun fetchEntry(id: AnimeId) = remoteCall { remote.fetchEntry(id) }.about(id)

            override suspend fun put(
                anime: Anime,
                values: SentValues?,
            ) = remoteCall { remote.put(anime, values) }.about(anime.id)

            // An answer about another title is the engine's own exception, and nothing of it is stored.
            private fun Outcome<ListEntry?>.about(id: AnimeId) =
                also { check(it !is Outcome.Success || it.value == null || it.value.anime.id == id) { WRONG_TITLE } }
        }

    private val local =
        object : LocalList {
            override fun observeEntries() = stores.observing(store.observeEntries())

            override fun observeEntry(id: AnimeId) = stores.observing(store.observeEntry(id))

            override fun observeEntriesOf(ids: Set<AnimeId>) = stores.observing(store.observeEntriesOf(ids))

            override suspend fun state() = stores.reading { store.state() }

            override suspend fun read(id: AnimeId) = stores.reading { store.read(id) }

            override suspend fun readAll() = stores.reading { store.readAll() }

            override suspend fun write(entries: Map<AnimeId, StoredEntry?>) = stores.writing { store.write(entries) }

            override suspend fun add(
                anime: Anime,
                entry: StoredEntry,
            ) = stores.writing { store.add(anime, entry) }

            override suspend fun download(
                titles: List<Anime>,
                entries: Map<AnimeId, StoredEntry?>,
                at: Instant?,
            ) = stores.writing { store.download(titles, entries, at) }

            override suspend fun restore(entries: Map<AnimeId, StoredEntry?>) = stores.writing { store.restore(entries) }

            override suspend fun clear(owner: SignInId?) = stores.writing { store.clear(owner) }
        }

    private val stores = StoreCalls(scope, unexpected)

    // Held for every change to the store and to the fields below it, never while MAL is asked.
    private val mutex = Mutex()

    // A failed request starts a new epoch of its title, which the requests still waiting from the old one are not in.
    private val epochs = mutableMapOf<AnimeId, Int>()

    // A sign-out and a new sign-in start a new list: nothing accepted for an earlier one is sent or stored.
    private var list = 0

    // The session the store was last made to agree with.
    @Volatile
    private var settled: Session? = null

    // The store's time of the last download, which only the engine changes.
    private val syncedAt = MutableStateFlow<Instant?>(null)

    // The requests of a title's current epoch that are not answered yet.
    private val pending = mutableMapOf<AnimeId, Int>()
    private val touched = mutableMapOf<AnimeId, Long>()
    private var changes = 0L

    // The change count at the last write MAL may have made unseen; a download begun after it ends what is owed.
    private var owedSince: Long? = null

    // Titles MAL may have changed unseen, until its answer to a write or a download that takes them says what it has.
    private val unsure = mutableSetOf<AnimeId>()
    private val accepted = mutableListOf<Request>()
    private var restored = false
    private var stopped = false

    private val syncs = Any()
    private var running: Deferred<Outcome<Unit>>? = null
    private var following: Deferred<Outcome<Unit>>? = null

    // One lane: W1's order and N9's read before its write both rely on no other answer coming between.
    private val queue = Channel<Request>(Channel.UNLIMITED)

    // W2: each failure goes to a waiting collector, else waits for the next one; both guarded by [held] itself.
    private val held = ArrayDeque<WriteFailure>()
    private val collectors = ArrayDeque<CompletableDeferred<WriteFailure>>()

    // Titles whose check after a download went unanswered, the longest unanswered first; guarded by the mutex.
    private val unanswered = LinkedHashSet<AnimeId>()

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
                            is Direct -> {
                                if (request.made) madeUnseen(request)
                                request.result.completeExceptionally(e)
                            }
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
                        if (request.made) madeUnseen(request)
                        Outcome.Failure(DataError.StorageError)
                    },
                )
        }
    }

    override fun observeEntries(): Flow<List<ListEntry>> = shown(emptyList()) { local.observeEntries() }

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> = shown(null) { local.observeEntry(id) }

    override fun observeEntriesOf(ids: Set<AnimeId>): Flow<Map<AnimeId, ListEntry>> = shown(emptyMap()) { local.observeEntriesOf(ids) }

    override fun observeSyncedAt(): Flow<Instant?> = shown(null) { syncedAt }

    // Nothing signed out, else the store's own once it holds this sign-in's list; it is tried a few times in a row.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> shown(
        nothing: T,
        stored: () -> Flow<T>,
    ): Flow<T> =
        sessions
            .distinctUntilChanged()
            .flatMapLatest { session ->
                if (session == Session.SignedOut) {
                    return@flatMapLatest flow {
                        if (settled != session) follow()
                        emit(nothing)
                    }
                }
                flow {
                    mutex.withLock { settle() }
                    emitAll(stored())
                }.retriedOverStore()
            }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeWriteFailures(): Flow<WriteFailure> =
        flow {
            while (true) {
                val next = synchronized(held) { held.removeFirstOrNull()?.let(::CompletableDeferred) ?: waiting() }
                val failure =
                    try {
                        next.await()
                    } catch (e: CancellationException) {
                        // A failure handed to a collector cut off before it heard it waits for the next one.
                        synchronized(held) { collectors.remove(next) }
                        if (next.isCompleted) hold(next.getCompleted())
                        throw e
                    }
                emit(failure)
            }
        }

    private fun waiting() = CompletableDeferred<WriteFailure>().also { collectors += it }

    private fun hold(failure: WriteFailure) {
        synchronized(held) {
            collectors.removeFirstOrNull()?.let {
                it.complete(failure)
                return
            }
            held += failure
            if (held.size > HELD_FAILURES) held.removeFirst()
        }
    }

    // W2: a failure still held goes once a Retry over MAL's settled values of its title would change nothing.
    private fun settled(
        id: AnimeId,
        entry: StoredEntry?,
    ) {
        if (entry?.unconfirmed == true) return
        val values = entry?.confirmed?.values
        synchronized(held) {
            held.removeAll { failure ->
                val write = failure.write
                write.anime.id == id && (write is ListWrite.Save || write is ListWrite.Remove) && failure.settledBy(values)
            }
        }
    }

    // W6: one download at a time; calls made while one runs share the one that follows it.
    override suspend fun sync(): Outcome<Unit> {
        if (session() !is Session.SignedIn) return Outcome.Failure(DataError.SessionExpired)
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

    // S6, W2: a refused write changes nothing, and its failure is one the shell offers no Retry for.
    override suspend fun write(write: ListWrite): ListChange? {
        val session = session()
        refused(write, session)?.let {
            hold(it)
            return null
        }
        return try {
            withContext(NonCancellable) {
                try {
                    locked {
                        check(!stopped) { STOPPED }
                        val refused = refused(write, checkNotNull(settled))
                        if (refused == null) edit(write) else null.also { hold(refused) }
                    }
                } catch (_: StoreFailure) {
                    hold(write.failed(DataError.StorageError, write.list ?: session.owner))
                    null
                }
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException(STOPPED, e)
        }
    }

    // An Undo or a Retry of another sign-in's list is for no list at all; any other write needs a signed-in session.
    private fun refusal(
        write: ListWrite,
        session: Session,
    ): DataError? =
        when {
            write.bound && write.list != session.owner -> DataError.ListEnded
            session !is Session.SignedIn -> DataError.SessionExpired
            else -> null
        }

    private fun refused(
        write: ListWrite,
        session: Session,
    ): WriteFailure? = refusal(write, session)?.let { write.failed(it, if (write.bound) write.list else session.owner) }

    // A Retry that fails again is still the failure it came from, and still leaves what has changed since.
    private fun ListWrite.failed(
        error: DataError,
        list: SignInId?,
    ): WriteFailure = if (this is ListWrite.Retry) failure.copy(error = error, list = list) else WriteFailure(error, this, list)

    // An unconfirmed title with nothing on its way to MAL, or one MAL may have changed unseen, is sent even unchanged.
    private suspend fun edit(write: ListWrite): ListChange? {
        val id = write.anime.id
        val stored = local.read(id)
        val anime = stored?.anime ?: write.anime
        val current = stored?.entry?.shown?.let { ListEntry(anime, it.values, it.updatedAt) }
        val plain = write.left(current?.values) ?: return null
        val values = plain.applyTo(current, today())
        val from = if (id in pending) current?.values else stored?.entry?.confirmed?.values
        val named = (plain as? ListWrite.Save)?.only ?: values?.let { v -> from?.let { v.changedFrom(it) } }
        val owed = stored?.entry?.unconfirmed == true && id !in pending || id in unsure && named?.isEmpty() != true
        if (values == current?.values && !owed) return null
        val next = values?.let { ListEntry(anime, it, clock.instant()) }
        val entry = StoredEntry(next?.record, stored?.entry?.confirmed, unconfirmed = true)
        touch(id)
        if (stored == null) local.add(anime, entry) else local.write(mapOf(id to entry))
        pending[id] = (pending[id] ?: 0) + 1
        accept(Put(anime, values, from, named, epochs[id] ?: 0, list))
        return ListChange(current, next, plain.steps, checkNotNull(settled?.owner))
    }

    override suspend fun send(write: ListWrite): Outcome<Unit> {
        refusal(write, session())?.let { return Outcome.Failure(it) }
        val result = CompletableDeferred<Outcome<Unit>>()
        val refusal =
            try {
                locked { refusal(write, checkNotNull(settled)) ?: null.also { accept(Direct(write, result, list)) } }
            } catch (_: StoreFailure) {
                DataError.StorageError
            }
        return refusal?.let { Outcome.Failure(it) } ?: result.await()
    }

    private fun accept(request: Request) {
        check(!stopped) { STOPPED }
        accepted += request
        queue.trySend(request)
    }

    // W4: a title edited since the download began keeps its values; every other one becomes what MAL has.
    private suspend fun download(): Outcome<Unit> =
        try {
            val (since, began) = locked { if (settled is Session.SignedIn) changes to list else null } ?: return expired
            when (val outcome = mal.fetch()) {
                is Outcome.Failure -> outcome
                is Outcome.Success -> {
                    val fetched = outcome.value.associateByTo(mutableMapOf()) { it.anime.id }
                    val missing =
                        locked {
                            if (list != began) return@locked emptySet()
                            local.readAll().keys - fetched.keys - pending.keys - touched.filterValues { it > since }.keys
                        }
                    val kept = missing - checked(missing, fetched, began)
                    locked {
                        if (settled !is Session.SignedIn || list != began) return@locked expired
                        val stored = local.readAll()
                        val changed =
                            (stored.keys + fetched.keys)
                                .filterNot { it in kept || it in pending || (touched[it] ?: 0) > since }
                                .associateWith { fetched[it]?.let { StoredEntry.settled(it.record) } }
                                .filter { (id, entry) -> entry != stored[id] }
                        val now = clock.instant().takeIf { owedSince.let { it == null || it <= since } }
                        local.download(fetched.values.map { it.anime }, changed, now)
                        unsure.removeAll { it !in kept && it !in pending && (touched[it] ?: 0) <= since }
                        changed.forEach { (id, entry) -> settled(id, entry) }
                        if (now != null) owedSince = null
                        syncedAt.value = now
                        Outcome.Success(Unit)
                    }
                }
            }
        } catch (_: StoreFailure) {
            Outcome.Failure(DataError.StorageError)
        }

    // 2.2: only MAL's answer removes a title a paged download missed; the titles it answered, at most [checksPerSync].
    private suspend fun checked(
        missing: Set<AnimeId>,
        fetched: MutableMap<AnimeId, ListEntry>,
        began: Int,
    ): Set<AnimeId> {
        val failed =
            mutex.withLock {
                unanswered.retainAll(missing)
                unanswered.toList()
            }
        val fresh = (missing - failed.toSet()).sortedBy { it.value }
        val start = if (fresh.isEmpty()) 0 else random.nextInt(fresh.size)
        val asked = (fresh.drop(start) + fresh.take(start) + failed).take(checksPerSync)
        val answered = mutableSetOf<AnimeId>()
        var inARow = 0
        for (id in asked) {
            if (mutex.withLock { list != began }) break
            val entry =
                try {
                    mal.fetchEntry(id)
                } catch (e: IllegalStateException) {
                    unexpected(e)
                    Outcome.Failure(DataError.ServerError)
                }
            val error = (entry as? Outcome.Failure)?.error
            if (error != null && error != DataError.NotFound) {
                mutex.withLock {
                    if (list == began) {
                        unanswered.remove(id)
                        unanswered += id
                    }
                }
                if (error in ENDS_CHECKS || ++inARow == FAILED_IN_A_ROW) break else continue
            }
            inARow = 0
            (entry as? Outcome.Success)?.value?.let { fetched[id] = it }
            answered += id
            mutex.withLock { unanswered.remove(id) }
        }
        return answered
    }

    // W1 and W2: one request at a time; a failure returns the title to the confirmed values and drops its waiting edits.
    private suspend fun send(put: Put) {
        val id = put.id
        val (signedIn, confirmed, unseen) =
            locked {
                if ((epochs[id] ?: 0) != put.epoch || list != put.list) return
                Triple(settled is Session.SignedIn, local.read(id)?.entry?.confirmed, id in unsure)
            }
        val sent = put.values?.sentOver(put.from, confirmed?.values, if (unseen) put.named ?: ListField.entries.toSet() else null)
        val asked = signedIn && (put.values != null || confirmed != null) && (put.values == null || sent != null)
        val outcome =
            when {
                !signedIn -> expired
                put.values == null && confirmed == null -> Outcome.Success(null)
                put.values != null && sent == null -> Outcome.Success(confirmed)
                else -> mal.put(put.anime, sent).recorded()
            }
        val failure =
            locked {
                if (list != put.list) return@locked null
                when (outcome) {
                    is Outcome.Success -> {
                        val waiting = (pending[id] ?: 1) - 1
                        if (waiting > 0) pending[id] = waiting else pending.remove(id)
                        if (asked) unsure.remove(id)
                        confirm(put.anime, outcome.value)
                        null
                    }
                    is Outcome.Failure -> {
                        epochs[id] = put.epoch + 1
                        pending.remove(id)
                        val stored = local.read(id)?.entry
                        val back = mapOf(id to stored?.confirmed?.let { StoredEntry.settled(it) })
                        touch(id)
                        if (outcome.error.unsure) owe(back) else local.write(back)
                        if (outcome.error.unsure) unsure += id
                        val shown = stored?.shown
                        val retry = shown?.let { ListWrite.Save(put.anime, it.values, touched(put)) } ?: ListWrite.Remove(put.anime)
                        val made = if (outcome.error.unsure) setOf(put.values) else emptySet()
                        WriteFailure(outcome.error, retry, settled?.owner, Untouched(setOf(stored?.confirmed?.values) + made))
                    }
                }
            }
        if (failure != null) {
            hold(failure)
            if (failure.error.unsure) scope.launch { sync() }
        }
    }

    // MAL may have made a write whose answer was lost, could not be read, or was cut off: a download says what it has.
    private val DataError.unsure: Boolean get() = this == DataError.ServerError || this == DataError.NoConnection

    // A Retry sets every value the failed edit and those waiting behind it set; null for all of them.
    private fun touched(put: Put): Set<ListField>? {
        val named = (accepted.filterIsInstance<Put>().filter { it.id == put.id && it.epoch == put.epoch } + put).map { it.named }
        return if (null in named) null else named.flatMapTo(mutableSetOf()) { checkNotNull(it) }.takeIf { it.isNotEmpty() }
    }

    // The list counts as never downloaded until a download begun after now succeeds, also across a process death (W5).
    private suspend fun owe(entries: Map<AnimeId, StoredEntry?>) {
        local.restore(entries)
        syncedAt.value = null
        owedSince = ++changes
    }

    // A request the store or the engine failed under is a failed write (W2) MAL may have made; its Retry carries the last edit.
    private suspend fun lost(
        put: Put,
        error: DataError,
    ) {
        val id = put.id
        var only: Set<ListField>? = null
        // Without the store, what the edit found and what it sent stand in for what MAL confirmed.
        var untouched = Untouched(setOf(put.from, put.values))
        val (last, owner) =
            mutex.withLock {
                try {
                    settle()
                } catch (_: StoreFailure) {
                }
                if (list != put.list) return
                epochs[id] = put.epoch + 1
                pending.remove(id)
                touch(id)
                try {
                    val confirmed = local.read(id)?.entry?.confirmed
                    untouched = Untouched(setOf(confirmed?.values, put.values))
                    local.restore(mapOf(id to confirmed?.let { StoredEntry.settled(it) }))
                } catch (_: StoreFailure) {
                }
                syncedAt.value = null
                owedSince = ++changes
                unsure += id
                only = touched(put)
                (accepted.filterIsInstance<Put>().lastOrNull { it.id == id && it.epoch == put.epoch } ?: put) to settled?.owner
            }
        val retry = last.values?.let { ListWrite.Save(last.anime, it, only) } ?: ListWrite.Remove(last.anime)
        hold(WriteFailure(error, retry, owner, untouched))
        scope.launch { sync() }
    }

    // N9: the write is applied to the entry as MAL has it now, and nothing is sent when that changes nothing.
    private suspend fun send(direct: Direct): Outcome<Unit> {
        val id = direct.id
        if (!current(direct)) return expired
        val confirmed =
            when (val outcome = mal.fetchEntry(id)) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value
            }
        val write = direct.write.left(confirmed?.values) ?: return Outcome.Success(Unit)
        val values = write.applyTo(confirmed, today())
        val refusal =
            when {
                confirmed == null && write !is ListWrite.Save && write !is ListWrite.Remove -> DataError.NotFound
                write is ListWrite.MarkWatched && confirmed != null && confirmed.values.watchedEpisodes < write.episode - 1 ->
                    DataError.InvalidRequest
                else -> null
            }
        if (refusal != null) return Outcome.Failure(refusal)
        val sent = values?.sentOver(confirmed?.values, confirmed?.values)
        val entry =
            if (values == confirmed?.values || (values != null && sent == null)) {
                confirmed
            } else {
                if (!current(direct)) return expired
                direct.made = true
                when (val outcome = mal.put(confirmed?.anime ?: write.anime, sent)) {
                    is Outcome.Failure -> return outcome.also { failedUnseen(direct, it.error) }
                    is Outcome.Success -> outcome.value
                }
            }
        locked {
            if (list == direct.list) {
                unsure -= id
                confirm(entry?.anime ?: write.anime, entry?.record)
            }
        }
        return Outcome.Success(Unit)
    }

    // N9's write owes a download on the same rule.
    private suspend fun failedUnseen(
        direct: Direct,
        error: DataError,
    ) {
        if (error.unsure) madeUnseen(direct)
    }

    // A write MAL may have made unseen marks its title and owes a download; a store that fails to keep that still gets it.
    private suspend fun madeUnseen(direct: Direct) {
        val owes =
            try {
                locked {
                    (list == direct.list).also {
                        if (it) {
                            touch(direct.id)
                            unsure += direct.id
                            syncedAt.value = null
                            owedSince = ++changes
                            local.restore(emptyMap())
                        }
                    }
                }
            } catch (_: StoreFailure) {
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unexpected(e)
                true
            }
        if (owes) scope.launch { sync() }
    }

    private fun Outcome<ListEntry?>.recorded(): Outcome<ListRecord?> =
        when (this) {
            is Outcome.Success -> Outcome.Success(value?.record)
            is Outcome.Failure -> this
        }

    private suspend fun current(request: Request): Boolean = locked { settled is Session.SignedIn && list == request.list }

    // MAL's answer is the confirmed entry, and the shown one when no edit is waiting and its values differ from it.
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
        settled(id, next)
        if (next == stored) return
        touch(id)
        if (title == null && next != null) local.add(anime, next) else local.write(mapOf(id to next))
    }

    // The store agrees with the session: nothing signed out (S9), else the list of its sign-in alone, expired too (S6).
    private suspend fun settle() {
        val session = sessions.first()
        if (session == settled) return
        val owner = session.owner
        val state = owner?.let { local.state() }
        if (state == null || state.owner != owner) {
            forget(owner)
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
        owedSince = null
        unsure.clear()
        syncedAt.value = null
        synchronized(held) { held.clear() }
        unanswered.clear()
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
        unsure += unconfirmed.keys
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

    // The session as it is; a store that does not agree with it yet, as after a clear that failed, is asked again.
    private suspend fun session(): Session {
        val session = sessions.first()
        if (session != settled) follow()
        return session
    }

    // A Retry is what is left of its failed write over [current]; null when nothing is.
    private fun ListWrite.left(current: ListValues?): ListWrite? = if (this is ListWrite.Retry) failure.left(current) else this

    private suspend inline fun <T> locked(block: () -> T): T =
        mutex.withLock {
            settle()
            block()
        }

    private suspend inline fun <T> remoteCall(call: () -> Outcome<T>): Outcome<T> =
        try {
            call()
        } catch (e: Exception) {
            stores.ended(e)
            when {
                sessions.first() !is Session.SignedIn -> expired
                e is IOException -> Outcome.Failure(DataError.NoConnection)
                else -> Outcome.Failure(DataError.ServerError).also { unexpected(e) }
            }
        }

    private fun touch(id: AnimeId) {
        touched[id] = ++changes
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    companion object {
        // Asked one after another; a user who removed more on the website sees them go over the next syncs.
        const val CHECKS_PER_SYNC = 20

        private const val STOPPED = "The list's scope has ended"
        private val expired = Outcome.Failure(DataError.SessionExpired)
        private const val WRONG_TITLE = "MAL's client answered about another title"
        private const val HELD_FAILURES = 16

        // A failing entry endpoint costs this many checks, while one broken title holds back nothing.
        private const val FAILED_IN_A_ROW = 2

        // Answers that say MAL will not answer the next check either.
        private val ENDS_CHECKS = setOf(DataError.Refused, DataError.NoConnection, DataError.SessionExpired)
    }
}
