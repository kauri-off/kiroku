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
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page =
                when (val answer = page(name, offset, repairs)) {
                    is Outcome.Failure -> return answer
                    is Outcome.Success -> answer.value
                }
            if (page.entries.any { !ids.add(it.id) }) return Outcome.Failure(DataError.ServerError)
            currentCoroutineContext().ensureActive()
            part(page.entries)
            currentCoroutineContext().ensureActive()
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
        repairs: MutableSet<String>,
    ): Outcome<PublicMangaPage> =
        http
            .call(listError = ::publicMangaListError) {
                val response = api.page(name, MangaListQuery + ("offset" to offset.toString()))
                if (!response.isSuccessful) throw HttpException(response)
                checkNotNull(response.body()).use { body ->
                    if (response.code() != 200 || body.contentType()?.subtype?.equals("json", ignoreCase = true) != true) {
                        throw NotAnAnswer()
                    }
                    val envelope = body.string().strictMangaObject() ?: throw NotAnAnswer()
                    check("error" !in envelope)
                    val page = envelope.mangaListPage(offset)
                    check(offset == 0 || page.entries.isNotEmpty())
                    PublicMangaPage(page.entries.map { it.toEntry(repairs) }, page.next)
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
): DataError? = DataError.UnknownUser.takeIf { status == 404 && body.strictMangaObject()?.get("error") == JsonPrimitive("not_found") }

private interface PublicMangaListApi {
    @GET("users/{user}/mangalist")
    suspend fun page(
        @Path("user") user: String,
        @QueryMap query: Map<String, String>,
    ): Response<ResponseBody>
}
