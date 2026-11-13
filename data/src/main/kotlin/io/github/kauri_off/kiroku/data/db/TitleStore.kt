package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

// A title's details as stored, and when MAL was last asked for them.
data class StoredDetails(
    val details: AnimeDetails,
    val fetchedAt: Instant,
)

// The titles in the database: every screen's cache of MAL's titles, written beside the list and never by an edit.
class TitleStore(
    private val database: KirokuDatabase,
    private val dispatcher: CoroutineDispatcher,
) {
    private val dao = database.titles()

    fun observeAnime(id: AnimeId): Flow<Anime?> =
        dao
            .observe(id.value)
            .distinctUntilChanged()
            .map { it?.toAnime() }
            .flowOn(dispatcher)

    fun observeDetails(id: AnimeId): Flow<StoredDetails?> =
        dao
            .observeDetails(id.value)
            .distinctUntilChanged()
            .map { stored -> stored?.let { StoredDetails(it.toDetails(), instantOf(it.details.fetchedAt)) } }
            .flowOn(dispatcher)

    // Titles known only in part, as a related row or a search result has them: stored only where none is stored.
    suspend fun addUnknown(titles: List<Anime>) = withContext(dispatcher) { dao.insertIfAbsent(titles.map { it.toRow() }) }

    // MAL's whole answer about one title, all or none; the titles it names are known only in part.
    suspend fun refresh(
        anime: Anime,
        details: AnimeDetails,
        at: Instant,
    ) {
        require(details.id == anime.id)
        val id = anime.id.value
        withContext(dispatcher) {
            val named = details.related.map { it.anime } + details.recommendations
            val related =
                details.related.mapIndexed { position, it ->
                    TitleRelationRow(id, position, it.anime.id.value, relationTypes.toColumn(it.relation))
                }
            val recommended = details.recommendations.mapIndexed { position, it -> TitleRecommendationRow(id, position, it.id.value) }
            database.transaction {
                dao.merge(listOf(anime.toRow()))
                dao.insertIfAbsent(named.map { it.toRow() })
                dao.mergeDetails(details.toRow(at), related, recommended)
            }
        }
    }
}
