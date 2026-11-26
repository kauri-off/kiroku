package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaListReader
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
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

class RoomMangaList internal constructor(
    database: () -> KirokuDatabase,
    private val session: MalSession,
    private val source: MangaListSource,
    private val clock: Clock,
    private val unexpected: (Throwable) -> Unit,
    private val retired: (SignInId) -> Boolean = { false },
) : MangaListReader {
    constructor(
        database: () -> KirokuDatabase,
        session: MalSession,
        http: MalHttp,
        clock: Clock,
        retired: (SignInId) -> Boolean,
    ) : this(database, session, MalMangaListSource(http, session), clock, http.unexpected, retired)

    private val database by lazy(database)
    private val dao get() = database.mangaList()

    private data class RefreshQueue(
        val owner: SignInId,
        val lock: Mutex = Mutex(),
    )

    private var syncing: RefreshQueue? = null

    override suspend fun stored(owner: SignInId): Outcome<MangaListSnapshot?> =
        whileOwner(owner) {
            val snapshot =
                database.transaction {
                    dao.state(owner.value)?.let { state ->
                        MangaListSnapshot(owner, dao.entries(owner.value).map { it.toEntry() }, instantOf(state.syncedAt))
                    }
                }
            Outcome.Success(snapshot)
        }

    override suspend fun refresh(
        owner: SignInId,
        canDispatch: () -> Boolean,
    ): Outcome<MangaListSnapshot>? =
        withContext(Dispatchers.IO) {
            val queue =
                whileOwner(owner) {
                    val current = syncing?.takeIf { it.owner == owner } ?: RefreshQueue(owner).also { syncing = it }
                    Outcome.Success(current)
                }
            val lock =
                when (queue) {
                    is Outcome.Failure -> return@withContext queue
                    is Outcome.Success -> queue.value.lock
                }
            lock.withLock {
                val current = session.currentSession() ?: return@withLock Outcome.Failure(DataError.StorageError)
                if (current.owner != owner) return@withLock Outcome.Failure(DataError.ListEnded)
                if (current is Session.Expired) return@withLock Outcome.Failure(DataError.SessionExpired)
                if (!canDispatch()) return@withLock null
                val entries =
                    when (val answer = fetch(owner, canDispatch)) {
                        null -> return@withLock whileOwner(owner) { Outcome.Success(null) }.let { it as? Outcome.Failure }
                        is Outcome.Failure ->
                            return@withLock whileOwner(owner) {
                                if (answer.error == DataError.ListEnded && session.currentSession() is Session.Expired) {
                                    Outcome.Failure(DataError.SessionExpired)
                                } else {
                                    answer
                                }
                            }
                        is Outcome.Success -> answer.value.sortedBy { it.id.value }
                    }
                val duplicate = (1 until entries.size).any { entries[it - 1].id == entries[it].id }
                if (duplicate) return@withLock whileOwner(owner) { Outcome.Failure(DataError.ServerError) }

                whileOwner(owner) {
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

    private suspend fun fetch(
        owner: SignInId,
        canDispatch: () -> Boolean,
    ): Outcome<List<MangaListEntry>>? =
        try {
            source.fetch(owner, canDispatch)
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
