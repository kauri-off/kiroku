package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.db.MangaListStateRow
import io.github.kauri_off.kiroku.data.db.UnreadableRow
import io.github.kauri_off.kiroku.data.db.instantOf
import io.github.kauri_off.kiroku.data.db.toColumn
import io.github.kauri_off.kiroku.data.db.toRow
import io.github.kauri_off.kiroku.data.db.transaction
import io.github.kauri_off.kiroku.data.network.UnusableAnswer
import io.github.kauri_off.kiroku.data.session.MalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

// Success supplies a complete, fully mapped list for the requested owner.
internal fun interface MangaListSource {
    suspend fun fetch(owner: SignInId): Outcome<List<MangaListEntry>>
}

internal class RoomMangaList(
    private val database: KirokuDatabase,
    private val session: MalSession,
    private val source: MangaListSource,
    private val clock: Clock,
    private val unexpected: (Throwable) -> Unit,
) {
    private val dao = database.mangaList()
    private val syncing = Mutex()

    suspend fun stored(owner: SignInId): Outcome<MangaListSnapshot?> =
        whileOwner(owner) {
            val snapshot =
                database.transaction {
                    dao.state(owner.value)?.let { state ->
                        MangaListSnapshot(owner, dao.entries(owner.value).map { it.toEntry() }, instantOf(state.syncedAt))
                    }
                }
            Outcome.Success(snapshot)
        }

    suspend fun refresh(owner: SignInId): Outcome<MangaListSnapshot> =
        syncing.withLock {
            val current = session.currentSession() ?: return@withLock Outcome.Failure(DataError.StorageError)
            if (current.owner != owner) return@withLock Outcome.Failure(DataError.ListEnded)
            if (current is Session.Expired) return@withLock Outcome.Failure(DataError.SessionExpired)
            val entries =
                when (val answer = fetch(owner)) {
                    is Outcome.Failure -> return@withLock whileOwner(owner) { answer }
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

    suspend fun forgetRetired(currentOwner: SignInId?): Outcome<Unit> =
        whileOwner(currentOwner) {
            database.transaction { dao.deleteAllBut(currentOwner?.value) }
            Outcome.Success(Unit)
        }

    private suspend fun fetch(owner: SignInId): Outcome<List<MangaListEntry>> =
        try {
            source.fetch(owner)
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
        try {
            var answer: Outcome<T> = Outcome.Failure(DataError.ListEnded)
            val owned =
                session.whileOwner(owner) {
                    answer = operation()
                    true
                }
            if (!owned && session.currentSession() == null) Outcome.Failure(DataError.StorageError) else answer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unexpected(UnreadableRow(e))
            Outcome.Failure(DataError.StorageError)
        }
}
