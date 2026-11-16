package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.airing.AiringStore
import io.github.kauri_off.kiroku.core.airing.StoredAiring
import io.github.kauri_off.kiroku.core.model.AnimeId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// Airing data in the database, mapped on [dispatcher]; [unexpected] hears of a title left out as unreadable.
class RoomAiringStore(
    private val database: KirokuDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val unexpected: (Throwable) -> Unit,
) : AiringStore {
    private val dao = database.airing()

    override fun observe(): Flow<Map<AnimeId, StoredAiring>> =
        dao
            .observeStored()
            .distinctUntilChanged()
            .map { titles(it) }
            .flowOn(dispatcher)

    override suspend fun read(): Map<AnimeId, StoredAiring> = withContext(dispatcher) { titles(dao.stored()) }

    override suspend fun write(titles: Map<AnimeId, StoredAiring?>) {
        val rows = titles.entries.associate { (id, airing) -> id.value to airing?.let { it.toRow(id.value) to it.toEpisodeRows(id.value) } }
        withContext(dispatcher) { database.transaction { dao.replace(rows) } }
    }

    // Forgets every title at once, also one whose rows cannot be read, which no read gives back to be forgotten.
    suspend fun clear() {
        withContext(dispatcher) { database.transaction { dao.clear() } }
    }

    private fun titles(rows: List<AiringEpisodeJoin>): Map<AnimeId, StoredAiring> =
        rows
            .groupBy { it.titleId }
            .mapNotNull { (id, title) -> readable(unexpected) { AnimeId(id) to title.toStored() } }
            .toMap()
}
