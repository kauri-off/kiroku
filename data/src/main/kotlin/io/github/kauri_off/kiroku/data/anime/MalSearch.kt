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
import retrofit2.http.GET
import retrofit2.http.Query

class MalSearch(
    private val http: MalHttp,
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
                val next = nextOffset(page.paging)
                check(next == null || next > offset) { "The next page is not after this one" }
                val repairs = Repairs().within("node")
                val titles = page.data.map { it.node.toAnime(repairs) }
                if (repairs.fields.isNotEmpty()) http.unexpected(RepairedAnswer(repairs.fields))
                Page(titles, next)
            }
        return answer.clientIdOutcome()
    }

    override suspend fun browse(
        list: BrowseList,
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<Ranked<Anime>>> = Outcome.Failure(DataError.ServerError)

    private companion object {
        const val PAGE_SIZE = 10
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
)

private interface SearchApi {
    @GET("anime")
    suspend fun search(
        @Query("q") query: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
        @Query("fields") fields: String,
        @Query("nsfw") nsfw: Boolean?,
    ): SearchPageNode
}
