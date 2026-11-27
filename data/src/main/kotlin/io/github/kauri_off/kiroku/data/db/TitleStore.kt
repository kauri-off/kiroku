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

// A value of MAL's page that the mapper could read only in part: a stored value of it stands.
enum class Unread { BroadcastTime, Genres, Studios, Related, Recommendations, Pictures }

// Whose list the database held when a refresh began: its answer is not stored once the list is another's (S9).
@JvmInline
value class Holder internal constructor(
    internal val owner: String?,
)

// The titles in the database, the cache of their pages; [unexpected] hears of a named title left out as unreadable.
class TitleStore(
    private val database: KirokuDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val unexpected: (Throwable) -> Unit,
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
            .map { stored -> stored?.let { StoredDetails(it.toDetails(unexpected), instantOf(it.details.fetchedAt)) } }
            .flowOn(dispatcher)

    fun observeDetailsOf(ids: Set<AnimeId>): Flow<Map<AnimeId, AnimeDetails>> =
        dao
            .observeDetails(ids.map { it.value })
            .distinctUntilChanged()
            .map { rows -> rows.associate { AnimeId(it.details.titleId) to it.toDetails(unexpected) } }
            .flowOn(dispatcher)

    fun observeDetailed(): Flow<Set<AnimeId>> =
        dao
            .observeDetailed()
            .map { ids -> ids.mapTo(HashSet(), ::AnimeId) }
            .distinctUntilChanged()
            .flowOn(dispatcher)

    // Titles known only in part, as a title's page names them: stored only where none is stored.
    suspend fun addUnknown(titles: List<Anime>) = withContext(dispatcher) { dao.insertIfAbsent(titles.map { it.toRow() }) }

    suspend fun holder(): Holder = withContext(dispatcher) { Holder(database.list().state().owner) }

    // MAL's whole answer about one title, all or none; false, storing nothing, once the list changed hands since [begun].
    suspend fun refresh(
        anime: Anime,
        details: AnimeDetails,
        at: Instant,
        unread: Set<Unread> = emptySet(),
        begun: Holder? = null,
    ): Boolean {
        require(details.id == anime.id)
        val id = anime.id.value
        return withContext(dispatcher) {
            // The titles it names are known only in part.
            val named = details.related.map { it.anime } + details.recommendations
            val related =
                details.related.mapIndexed { position, it ->
                    TitleRelationRow(id, position, it.anime.id.value, relationTypes.toColumn(it.relation))
                }
            val recommended = recommendationsOf(id, details.recommendations)
            database.transaction {
                if (begun != null && database.list().state().owner != begun.owner) return@transaction false
                dao.merge(listOf(anime.toRow()), unread)
                dao.insertIfAbsent(named.map { it.toRow() })
                dao.mergeDetails(details.toRow(at), related, recommended, unread)
                true
            }
        }
    }

    internal suspend fun refreshRecommendations(
        id: AnimeId,
        titles: List<Anime>,
        includeAdult: Boolean,
        begun: Holder,
    ): Boolean =
        withContext(dispatcher) {
            database.transaction {
                if (database.list().state().owner != begun.owner) return@transaction false
                dao.mergeRecommendations(id.value, includeAdult, titles.map { it.toRow() }, recommendationsOf(id.value, titles))
            }
        }

    private fun recommendationsOf(
        id: Long,
        titles: List<Anime>,
    ) = titles.mapIndexed { position, title -> TitleRecommendationRow(id, position, title.id.value) }
}
