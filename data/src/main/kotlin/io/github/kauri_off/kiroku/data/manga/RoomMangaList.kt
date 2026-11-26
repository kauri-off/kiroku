package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListEditResult
import io.github.kauri_off.kiroku.core.repository.MangaListEditor
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.db.MangaListStateRow
import io.github.kauri_off.kiroku.data.db.UnreadableRow
import io.github.kauri_off.kiroku.data.db.instantOf
import io.github.kauri_off.kiroku.data.db.toColumn
import io.github.kauri_off.kiroku.data.db.toRow
import io.github.kauri_off.kiroku.data.db.transaction
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.UnusableAnswer
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.fileRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

// Success supplies a complete, fully mapped list for the requested owner.
internal fun interface MangaListSource {
    suspend fun fetch(
        owner: SignInId,
        canDispatch: () -> Boolean,
    ): Outcome<List<MangaListEntry>>?
}

internal fun interface MangaListWriter {
    suspend fun patch(
        owner: SignInId,
        id: MangaId,
        changes: MangaListEdit,
    ): MangaPatchResult
}

internal sealed interface MangaPatchResult {
    data object Accepted : MangaPatchResult

    data class Refused(
        val error: DataError,
    ) : MangaPatchResult

    data class Unknown(
        val error: DataError,
    ) : MangaPatchResult
}

class RoomMangaList internal constructor(
    database: () -> KirokuDatabase,
    private val session: MalSession,
    private val source: MangaListSource,
    private val clock: Clock,
    private val unexpected: (Throwable) -> Unit,
    private val retired: (SignInId) -> Boolean = { false },
    private val writer: MangaListWriter? = null,
) : MangaListEditor {
    private constructor(
        database: () -> KirokuDatabase,
        session: MalSession,
        source: MalMangaListSource,
        clock: Clock,
        unexpected: (Throwable) -> Unit,
        retired: (SignInId) -> Boolean,
    ) : this(database, session, source, clock, unexpected, retired, source)

    constructor(
        database: () -> KirokuDatabase,
        session: MalSession,
        http: MalHttp,
        clock: Clock,
        retired: (SignInId) -> Boolean,
    ) : this(database, session, MalMangaListSource(http, session, retired), clock, http.unexpected, retired)

    private val database by lazy(database)
    private val dao get() = database.mangaList()

    private class OwnerQueue {
        val queue = MangaListQueue()
        var users = 0
    }

    private val queues = mutableMapOf<SignInId, OwnerQueue>()

    override suspend fun stored(owner: SignInId): Outcome<MangaListSnapshot?> =
        whileOwner(owner) {
            val snapshot =
                database.transaction {
                    dao.state(owner.value)?.let { state ->
                        val at = instantOf(state.syncedAt).takeUnless { state.needsRefresh }
                        MangaListSnapshot(owner, dao.entries(owner.value).map { it.toEntry() }, at)
                    }
                }
            Outcome.Success(snapshot)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(owner: SignInId): Flow<Outcome<MangaListSnapshot?>> =
        session
            .observeOwner()
            .transformLatest { current ->
                if (current.id != owner || retired(owner)) {
                    emit(Outcome.Failure(DataError.ListEnded))
                } else {
                    val changes = database.invalidationTracker.createFlow("manga_list_state", "manga_list_entry")
                    emitAll(changes.map { stored(owner) })
                }
            }.onStart {
                if (session.currentSession() == null) emit(Outcome.Failure(DataError.StorageError))
            }.catch {
                if (it !is Exception || it is CancellationException) throw it
                unexpected(UnreadableRow(it))
                emit(Outcome.Failure(DataError.StorageError))
            }.flowOn(Dispatchers.IO)
            .map {
                if (it is Outcome.Failure) return@map it
                if (!session.mayObserveAs(owner)) return@map Outcome.Failure(DataError.ListEnded)
                when (val retirement = storage { Outcome.Success(retired(owner)) }) {
                    is Outcome.Failure -> retirement
                    is Outcome.Success ->
                        if (retirement.value || !session.mayObserveAs(owner)) Outcome.Failure(DataError.ListEnded) else it
                }
            }.distinctUntilChanged()

    override suspend fun refresh(
        owner: SignInId,
        canDispatch: () -> Boolean,
    ): Outcome<MangaListSnapshot>? =
        queued(owner) { queue ->
            queue.lock.withLock { withContext(Dispatchers.IO) { download(owner, canDispatch) } }
        }

    override suspend fun edit(
        owner: SignInId,
        id: MangaId,
        changes: MangaListEdit,
        retryFrom: MangaListEntry?,
    ): MangaListEditResult {
        if (id.value <= 0) return MangaListEditResult.Failed(DataError.NotFound)
        if (changes.isEmpty || writer == null || (retryFrom != null && retryFrom.id != id)) {
            return MangaListEditResult.Failed(DataError.InvalidRequest)
        }
        return queued(owner) { queue ->
            queue.edit(id) { withContext(Dispatchers.IO) { save(owner, id, changes, retryFrom, writer) } }
        }
    }

    private suspend fun <T> queued(
        owner: SignInId,
        run: suspend (MangaListQueue) -> T,
    ): T {
        val active = synchronized(queues) { queues.getOrPut(owner, ::OwnerQueue).also { it.users++ } }
        try {
            return run(active.queue)
        } finally {
            synchronized(queues) {
                if (--active.users == 0) queues.remove(owner)
            }
        }
    }

    private suspend fun save(
        owner: SignInId,
        id: MangaId,
        changes: MangaListEdit,
        retryFrom: MangaListEntry?,
        writer: MangaListWriter,
    ): MangaListEditResult {
        val prepared =
            whileOwner<MangaListEdit?>(owner) {
                if (!session.isSignedInAs(owner)) return@whileOwner Outcome.Failure(DataError.SessionExpired)
                database.transaction {
                    val state = dao.state(owner.value) ?: return@transaction Outcome.Failure(DataError.NotFound)
                    if (state.needsRefresh) return@transaction Outcome.Success(null)
                    val entry = dao.entry(owner.value, id.value) ?: return@transaction Outcome.Failure(DataError.NotFound)
                    val patch = if (retryFrom == null) changes else changes.remainingAfter(retryFrom, entry.toEntry())
                    if (!patch.isEmpty) dao.requireRefresh(owner.value, true)
                    Outcome.Success(patch)
                }
            }
        val patch =
            when (prepared) {
                is Outcome.Failure -> return MangaListEditResult.Failed(prepared.error)
                is Outcome.Success -> prepared.value ?: return MangaListEditResult.RefreshRequired
            }
        if (patch.isEmpty) return MangaListEditResult.Discarded

        val sent =
            when (val answer = remote { Outcome.Success(writer.patch(owner, id, patch)) }) {
                is Outcome.Success -> answer.value
                is Outcome.Failure -> MangaPatchResult.Unknown(answer.error)
                null -> error("A manga edit cannot be deferred")
            }
        if (sent is MangaPatchResult.Refused) {
            val cleared =
                whileOwner(owner) {
                    dao.requireRefresh(owner.value, false)
                    Outcome.Success(Unit)
                }
            val ended = cleared == Outcome.Failure(DataError.ListEnded)
            return MangaListEditResult.Failed(if (ended) DataError.ListEnded else sent.error)
        }

        val readBack = checkNotNull(download(owner) { true })
        if (readBack == Outcome.Failure(DataError.ListEnded)) return MangaListEditResult.Failed(DataError.ListEnded)
        val entry =
            when (readBack) {
                is Outcome.Failure -> readBack
                is Outcome.Success -> {
                    val returned = readBack.value.entries.firstOrNull { it.id == id }
                    when {
                        returned == null -> Outcome.Failure(DataError.NotFound)
                        !patch.hasEvidenceIn(returned) -> Outcome.Failure(DataError.ServerError)
                        else -> Outcome.Success(returned)
                    }
                }
            }
        if (entry is Outcome.Success && patch.isPresentIn(entry.value)) return MangaListEditResult.Confirmed(entry.value)
        if (sent == MangaPatchResult.Accepted) return MangaListEditResult.Accepted(entry)
        check(sent is MangaPatchResult.Unknown)
        return MangaListEditResult.Uncertain(sent.error, needsRefresh = entry is Outcome.Failure && entry.error != DataError.NotFound)
    }

    private suspend fun download(
        owner: SignInId,
        canDispatch: () -> Boolean,
    ): Outcome<MangaListSnapshot>? {
        val current = session.currentSession() ?: return Outcome.Failure(DataError.StorageError)
        if (current.owner != owner || retired(owner)) return Outcome.Failure(DataError.ListEnded)
        if (current is Session.Expired) return Outcome.Failure(DataError.SessionExpired)
        if (!canDispatch()) return null
        val answer = remote { source.fetch(owner, canDispatch) }
        val entries =
            when (answer) {
                null -> return whileOwner(owner) { Outcome.Success(null) } as? Outcome.Failure
                is Outcome.Failure ->
                    return whileOwner(owner) {
                        if (answer.error == DataError.ListEnded && session.currentSession() is Session.Expired) {
                            Outcome.Failure(DataError.SessionExpired)
                        } else {
                            answer
                        }
                    }
                is Outcome.Success -> answer.value.sortedBy { it.id.value }
            }
        val duplicate = (1 until entries.size).any { entries[it - 1].id == entries[it].id }
        if (duplicate) return whileOwner(owner) { Outcome.Failure(DataError.ServerError) }

        return whileOwner(owner) {
            val at = clock.instant()
            database.transaction {
                dao.deleteAllBut(owner.value)
                dao.saveState(MangaListStateRow(owner.value, at.toColumn()))
                dao.deleteEntries(owner.value)
                dao.insertEntries(entries.map { it.toRow(owner) })
            }
            Outcome.Success(MangaListSnapshot(owner, entries, at))
        }
    }

    suspend fun sweepRetired(owner: SessionOwner) {
        var attempt = 0
        while (forgetRetired(owner) == Outcome.Failure(DataError.StorageError)) {
            delay(fileRetry(attempt++).toMillis())
        }
    }

    internal suspend fun forgetRetired(owner: SessionOwner): Outcome<Unit> =
        storage {
            session.withOwner(owner) {
                forget(owner.id?.takeUnless(retired))
                Outcome.Success(Unit)
            } ?: Outcome.Failure(DataError.ListEnded)
        }

    // Called with the session gate already held.
    suspend fun forgetBeforeSignOut(): Outcome<Unit> =
        storage {
            forget(null)
            Outcome.Success(Unit)
        }

    private suspend fun forget(owner: SignInId?) = database.transaction { dao.deleteAllBut(owner?.value) }

    private suspend fun <T> remote(operation: suspend () -> Outcome<T>?): Outcome<T>? =
        try {
            operation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DataException) {
            Outcome.Failure(e.error)
        } catch (e: Exception) {
            unexpected(UnusableAnswer(e))
            Outcome.Failure(DataError.ServerError)
        }

    private suspend fun <T> whileOwner(
        owner: SignInId?,
        operation: suspend () -> Outcome<T>,
    ): Outcome<T> =
        storage {
            var answer: Outcome<T> = Outcome.Failure(DataError.ListEnded)
            val owned =
                session.whileOwner(owner) {
                    if (owner == null || !retired(owner)) answer = operation()
                    true
                }
            if (!owned && session.currentSession() == null) Outcome.Failure(DataError.StorageError) else answer
        }

    private suspend fun <T> storage(operation: suspend () -> Outcome<T>): Outcome<T> =
        withContext(Dispatchers.IO) {
            try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unexpected(UnreadableRow(e))
                Outcome.Failure(DataError.StorageError)
            }
        }
}
