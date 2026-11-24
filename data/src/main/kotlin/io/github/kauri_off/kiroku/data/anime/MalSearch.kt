package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.Ranked
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.network.clientIdOutcome
import io.github.kauri_off.kiroku.data.network.nextOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.Clock
import java.time.LocalDate

class MalSearch(
    private val http: MalHttp,
    private val clock: Clock,
) : SearchRepository {
    private val api by http.api(SearchApi::class)

    override suspend fun search(
        query: String,
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<Anime>> {
        val text = query.trim()
        if (text.isEmpty() || offset < 0) return Outcome.Failure(DataError.InvalidRequest)
        val answer =
            http.call {
                val page = api.search(text, offset, PAGE_SIZE, MalFields.SUMMARY, nsfw = if (includeAdult) true else null)
                page.mapPage(offset) { hit, repairs -> hit.node.toAnime(repairs.within("node")) }
            }
        return answer.clientIdOutcome()
    }

    override suspend fun browse(
        list: BrowseList,
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<Ranked<Anime>>> {
        if (offset < 0) return Outcome.Failure(DataError.InvalidRequest)
        val nsfw = true.takeIf { includeAdult }
        return http
            .call {
                val page =
                    when (list) {
                        BrowseList.ThisSeason -> {
                            val today = LocalDate.now(clock)
                            val season = SEASONS[(today.monthValue - 1) / 3]
                            api.season(today.year, season, offset, PAGE_SIZE, MalFields.SUMMARY, nsfw)
                        }
                        BrowseList.TopAiring -> api.ranking("airing", offset, PAGE_SIZE, MalFields.SUMMARY, nsfw)
                        BrowseList.TopRated -> api.ranking("all", offset, PAGE_SIZE, MalFields.SUMMARY, nsfw)
                        BrowseList.MostPopular -> api.ranking("bypopularity", offset, PAGE_SIZE, MalFields.SUMMARY, nsfw)
                    }
                page.mapPage(offset) { hit, repairs ->
                    val position = if (list == BrowseList.ThisSeason) null else hit.position(repairs)
                    Ranked(position, hit.node.toAnime(repairs.within("node")))
                }
            }.clientIdOutcome()
    }

    private fun <T> SearchPageNode.mapPage(
        offset: Int,
        map: (SearchHitNode, Repairs) -> T,
    ): Page<T> {
        val next = nextOffset(paging)
        check(next == null || next > offset) { "The next page is not after this one" }
        val repairs = Repairs()
        val items = data.map { map(it, repairs) }
        if (repairs.fields.isNotEmpty()) http.unexpected(RepairedAnswer(repairs.fields))
        return Page(items, next)
    }

    private companion object {
        const val PAGE_SIZE = 10
        val SEASONS = listOf("winter", "spring", "summer", "fall")
    }
}

@Serializable
private class SearchPageNode(
    val data: List<SearchHitNode>,
    val paging: JsonElement? = null,
)

@Serializable
private class SearchHitNode(
    val node: AnimeNode,
    val ranking: JsonElement? = null,
) {
    fun position(repairs: Repairs): Int? {
        val rank = (ranking as? JsonObject)?.get("rank") as? JsonPrimitive
        return repairs.read("ranking.rank", given = true, rank?.intOrNull?.takeIf { it > 0 })
    }
}

private interface SearchApi {
    @GET("anime")
    suspend fun search(
        @Query("q") query: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
        @Query("fields") fields: String,
        @Query("nsfw") nsfw: Boolean?,
    ): SearchPageNode

    @GET("anime/season/{year}/{season}")
    suspend fun season(
        @Path("year") year: Int,
        @Path("season") season: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
        @Query("fields") fields: String,
        @Query("nsfw") nsfw: Boolean?,
    ): SearchPageNode

    @GET("anime/ranking")
    suspend fun ranking(
        @Query("ranking_type") type: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
        @Query("fields") fields: String,
        @Query("nsfw") nsfw: Boolean?,
    ): SearchPageNode
}
