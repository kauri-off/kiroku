package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.repository.MangaCatalogue
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.Ranked
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
import kotlinx.serialization.json.longOrNull
import retrofit2.http.GET
import retrofit2.http.Query

class MalMangaCatalogue(
    private val http: MalHttp,
) : MangaCatalogue {
    private val api by http.api(CatalogueApi::class)

    override suspend fun search(
        query: String,
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<MangaDetails>> {
        val text = query.trim()
        if (text.isEmpty() || offset < 0) return Outcome.Failure(DataError.InvalidRequest)
        return http
            .call {
                api
                    .search(text, offset, PAGE_SIZE, MalFields.MANGA_SUMMARY, nsfw = true.takeIf { includeAdult })
                    .mapPage(offset) { hit, repairs -> hit.manga(repairs, includeAdult) }
            }.clientIdOutcome()
    }

    override suspend fun ranking(
        offset: Int,
        includeAdult: Boolean,
        canDispatch: () -> Boolean,
    ): Outcome<Page<Ranked<MangaDetails>>>? {
        if (offset < 0) return Outcome.Failure(DataError.InvalidRequest)
        val answer =
            http
                .call(maySendRead = canDispatch) {
                    api
                        .ranking("all", offset, PAGE_SIZE, MalFields.MANGA_SUMMARY, nsfw = true.takeIf { includeAdult })
                        .mapPage(offset) { hit, repairs -> Ranked(hit.position(repairs), hit.manga(repairs, includeAdult)) }
                }.clientIdOutcome()
        return answer.takeUnless { it == Outcome.Failure(DataError.ListEnded) }
    }

    private fun <T> CataloguePage.mapPage(
        offset: Int,
        map: (CatalogueHit, MutableSet<String>) -> T,
    ): Page<T> {
        val next = nextOffset(paging)
        check(next == null || next > offset)
        val repairs = mutableSetOf<String>()
        val items = data.map { map(it, repairs) }
        if (repairs.isNotEmpty()) http.unexpected(RepairedAnswer(repairs))
        return Page(items, next)
    }

    private companion object {
        const val PAGE_SIZE = 10
    }
}

@Serializable
private class CataloguePage(
    val data: List<CatalogueHit>,
    val paging: JsonElement? = null,
)

@Serializable
private class CatalogueHit(
    val node: JsonObject,
    val ranking: JsonElement? = null,
) {
    fun manga(
        repairs: MutableSet<String>,
        includeAdult: Boolean,
    ): MangaDetails {
        val id = (node["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
        val manga = node.toManga(MangaId(requireNotNull(id)), repairs)
        return if (!includeAdult && manga.safeForSearch == null) manga.copy(safeForSearch = true) else manga
    }

    fun position(repairs: MutableSet<String>): Int? {
        val rank = ((ranking as? JsonObject)?.get("rank") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        if (rank == null) repairs += "ranking.rank"
        return rank
    }
}

private interface CatalogueApi {
    @GET("manga")
    suspend fun search(
        @Query("q") query: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
        @Query("fields") fields: String,
        @Query("nsfw") nsfw: Boolean?,
    ): CataloguePage

    @GET("manga/ranking")
    suspend fun ranking(
        @Query("ranking_type") type: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
        @Query("fields") fields: String,
        @Query("nsfw") nsfw: Boolean?,
    ): CataloguePage
}
