package io.github.kauri_off.kiroku.data.db

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// The signed-in user's profile in the database, read on [dispatcher]; [unexpected] hears of a row left out as unreadable.
class RoomProfileStore(
    private val database: KirokuDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val unexpected: (Throwable) -> Unit,
) {
    private val dao = database.profile()

    internal fun observe(): Flow<StoredProfile?> =
        dao
            .observe()
            .distinctUntilChanged()
            .map { rows -> rows.firstOrNull()?.let { readable(unexpected) { it.toStored() } } }
            .flowOn(dispatcher)

    // Stores [stored] in place of any other, unless [owns], asked inside the write, says its owner no longer holds the session.
    internal suspend fun replace(
        stored: StoredProfile,
        owns: suspend () -> Boolean,
    ): Boolean =
        withContext(dispatcher) {
            database.transaction {
                if (owns()) {
                    dao.replace(stored.toRow())
                    true
                } else {
                    false
                }
            }
        }
}
