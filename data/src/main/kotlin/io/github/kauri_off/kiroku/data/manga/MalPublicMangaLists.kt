package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.user.RemoteMangaLists
import io.github.kauri_off.kiroku.data.list.isUserSegment
import io.github.kauri_off.kiroku.data.list.sameMalUser
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.NotAnAnswer
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.network.clientIdOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.QueryMap

class MalPublicMangaLists(
    private val http: MalHttp,
) : RemoteMangaLists {
    private val api by http.api(PublicMangaListApi::class, followRedirects = false)

    override suspend fun read(
        name: String,
        part: suspend (List<MangaListEntry>) -> Unit,
    ): Outcome<Unit> {
        if (!isUserSegment(name)) return Outcome.Failure(DataError.UnknownUser)
        val ids = mutableSetOf<MangaId>()
        val repairs = mutableSetOf<String>()
        var previousOffset: Int? = null
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page =
                when (val answer = page(name, offset, previousOffset, ids, repairs)) {
                    is Outcome.Failure -> return answer
                    is Outcome.Success -> answer.value
                }
            page.entries.forEach { ids.add(it.id) }
            currentCoroutineContext().ensureActive()
            part(page.entries)
            currentCoroutineContext().ensureActive()
            previousOffset = offset
            offset = page.next ?: break
        }
        if (repairs.isNotEmpty()) http.unexpected(RepairedAnswer(repairs))
        return Outcome.Success(Unit)
    }

    override fun sameUser(
        a: String,
        b: String,
    ): Boolean = sameMalUser(a, b)

    private suspend fun page(
        name: String,
        offset: Int,
        previousOffset: Int?,
        seen: Set<MangaId>,
        repairs: MutableSet<String>,
    ): Outcome<PublicMangaPage> =
        http
            .call(listError = ::publicMangaListError) {
                val response = api.page(name, MangaListQuery + ("offset" to offset.toString()))
                if (!response.isSuccessful) throw HttpException(response)
                checkNotNull(response.body()).use { body ->
                    if (response.code() != 200 || !response.headers().isMangaJson()) {
                        throw NotAnAnswer()
                    }
                    val envelope = body.string().uniqueMangaObject() ?: throw NotAnAnswer()
                    check("error" !in envelope)
                    val paging = envelope.getValue("paging").jsonObject
                    check("previous" !in paging || (previousOffset != null && paging.mangaOffset("previous") == previousOffset))
                    val page = envelope.mangaListPage(offset)
                    check(offset == 0 || page.entries.isNotEmpty())
                    val entries = page.entries.map { it.toEntry(repairs) }
                    val pageIds = mutableSetOf<MangaId>()
                    check(entries.all { it.id !in seen && pageIds.add(it.id) })
                    PublicMangaPage(entries, page.next)
                }
            }.clientIdOutcome()
}

private class PublicMangaPage(
    val entries: List<MangaListEntry>,
    val next: Int?,
)

private fun publicMangaListError(
    status: Int,
    body: String,
    headers: Headers?,
): DataError? {
    if ((status != 403 && status != 404) || headers?.isMangaJson() != true) return null
    val values = body.uniqueMangaObject() ?: return null
    return when {
        status == 403 && values["error"] == JsonPrimitive("not_permitted") && (values["message"] as? JsonPrimitive)?.isString == true ->
            DataError.PrivateList
        status == 404 && values["error"] == JsonPrimitive("not_found") -> DataError.UnknownUser
        else -> null
    }
}

private fun Headers.isMangaJson(): Boolean {
    val mediaType = values("Content-Type").singleOrNull()?.toMediaTypeOrNull()
    return mediaType?.type == "application" && mediaType.subtype == "json"
}

private interface PublicMangaListApi {
    @GET("users/{user}/mangalist")
    suspend fun page(
        @Path("user") user: String,
        @QueryMap query: Map<String, String>,
    ): Response<ResponseBody>
}
