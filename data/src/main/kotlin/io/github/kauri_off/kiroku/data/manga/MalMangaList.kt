package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.readingStatus
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.network.ReadAdmission
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.authorised
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.QueryMap

internal class MalMangaList(
    private val http: MalHttp,
    private val session: MalSession,
    private val retired: (SignInId) -> Boolean = { false },
) {
    private val api by http.api(MangaListApi::class)

    suspend fun fetch(
        expectedOwner: SignInId,
        firstRead: (() -> ReadAdmission)? = null,
    ): Outcome<MangaListDownload> {
        val entries = mutableListOf<MangaListRow>()
        val ids = mutableSetOf<MangaId>()
        var offset = 0
        while (true) {
            val page =
                when (val answer = page(offset, expectedOwner, firstRead.takeIf { offset == 0 })) {
                    is Outcome.Failure -> return answer
                    is Outcome.Success -> answer.value
                }
            for (entry in page.entries) {
                if (!ids.add(entry.id)) return Outcome.Failure(DataError.ServerError)
                entries += entry
            }
            offset = page.next ?: return Outcome.Success(MangaListDownload(expectedOwner, entries))
        }
    }

    suspend fun patch(
        id: MangaId,
        patch: MangaListEdit,
        expectedOwner: SignInId,
        onHttpFailure: (DataError) -> Unit = {},
    ): Outcome<MangaListWriteAnswer> {
        if (id.value <= 0) return Outcome.Failure(DataError.NotFound)
        val form = patch.form()
        if (form.isEmpty()) return Outcome.Failure(DataError.InvalidRequest)
        return request(expectedOwner, write = true, onHttpFailure = onHttpFailure) { api.patch(id.value, form, it).writeAnswer() }
    }

    suspend fun delete(
        id: MangaId,
        expectedOwner: SignInId,
    ): Outcome<MangaListWriteAnswer> {
        if (id.value <= 0) return Outcome.Failure(DataError.NotFound)
        return request(expectedOwner, write = true) { api.delete(id.value, it).writeAnswer() }
    }

    private suspend fun page(
        offset: Int,
        expectedOwner: SignInId,
        firstRead: (() -> ReadAdmission)?,
    ): Outcome<Page> =
        request(expectedOwner, firstRead) { authorization ->
            val response = api.page(Asked + ("offset" to offset.toString()), authorization)
            response.checkUnredirectedSuccess()
            val json =
                checkNotNull(response.body()).use {
                    check(response.code() == 200) { "The manga list page did not return OK" }
                    MalJson.parseToJsonElement(it.string()).jsonObject
                }
            val data = json.getValue("data") as JsonArray
            check(data.size <= MAX_ENTRIES - offset) { "The manga list exceeds its bound" }
            val end = offset + data.size
            val next = json.getValue("paging").jsonObject.mangaNextOffset()
            check(next == null || (data.isNotEmpty() && next == end && next < MAX_ENTRIES)) {
                "The next manga list page does not follow this one"
            }
            Page(data.map { it.jsonObject.membership() }, next)
        }

    private suspend fun <T> request(
        expectedOwner: SignInId,
        firstRead: (() -> ReadAdmission)? = null,
        write: Boolean = false,
        onHttpFailure: (DataError) -> Unit = {},
        send: suspend (String) -> T,
    ): Outcome<T> {
        val writeAllowed: (() -> Boolean)? = if (write) ({ !retired(expectedOwner) }) else null
        val answer = session.authorised(http, expectedOwner, firstRead, writeAllowed, onHttpFailure, send)
        val current = session.currentSession()
        return when {
            current != null && current.owner != expectedOwner -> Outcome.Failure(DataError.ListEnded)
            current is Session.Expired && answer == Outcome.Failure(DataError.ListEnded) -> Outcome.Failure(DataError.SessionExpired)
            else -> answer
        }
    }

    private class Page(
        val entries: List<MangaListRow>,
        val next: Int?,
    )

    private companion object {
        const val MAX_ENTRIES = 100_000
        const val VALUES = "status,score,num_chapters_read,num_volumes_read,updated_at"
        const val FIELDS = "id,title,main_picture,alternative_titles,media_type,status,num_chapters,num_volumes,list_status{$VALUES}"
        val Asked = mapOf("fields" to FIELDS, "nsfw" to "true", "sort" to "manga_title", "limit" to "1000")
    }
}

// Membership-checked rows still require private field mapping before cache replacement.
internal class MangaListDownload(
    val owner: SignInId,
    val rows: List<MangaListRow>,
)

internal class MangaListRow(
    val id: MangaId,
    val statusCode: String,
    val fields: JsonObject,
)

private fun JsonObject.membership(): MangaListRow {
    val node = getValue("node").jsonObject
    val value = (node["id"] as? JsonPrimitive)?.takeUnless { it.isString }
    val id = value?.longOrNull
    check(id != null && id > 0 && value.content == id.toString()) { "Invalid manga list identity" }
    val values = getValue("list_status").jsonObject
    val status = (values["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    check(!status.isNullOrBlank()) { "Missing manga list status" }
    return MangaListRow(MangaId(id), status, this)
}

private fun JsonObject.mangaNextOffset(): Int? {
    val next = get("next")
    if (next == null || next is JsonNull) return null
    val url = (next as? JsonPrimitive)?.takeIf { it.isString }?.content?.toHttpUrlOrNull()
    val offset = url?.queryParameterValues("offset")?.singleOrNull()?.toIntOrNull()
    return checkNotNull(offset?.takeIf { it >= 0 }) { "Invalid manga list offset" }
}

internal fun MangaListEdit.form(): Map<String, String> =
    buildMap {
        status?.let { put("status", it.readingStatus.code) }
        score?.let { put("score", it.toString()) }
        chaptersRead?.let { put("num_chapters_read", it.toString()) }
        volumesRead?.let { put("num_volumes_read", it.toString()) }
    }

internal class MangaListWriteAnswer(
    val status: Int,
    val body: String?,
    val mediaType: String?,
)

private fun Response<ResponseBody>.writeAnswer(): MangaListWriteAnswer {
    checkUnredirectedSuccess()
    val type = headers()["Content-Type"]
    return MangaListWriteAnswer(code(), body()?.use { it.string() }, type)
}

private fun Response<ResponseBody>.checkUnredirectedSuccess() {
    if (generateSequence(raw().priorResponse) { it.priorResponse }.any { it.isRedirect }) {
        body()?.close()
        errorBody()?.close()
        error("Redirected manga list request")
    }
    if (!isSuccessful) throw HttpException(this)
}

private const val ENTRY = "manga/{id}/my_list_status"

private interface MangaListApi {
    @GET("users/@me/mangalist")
    suspend fun page(
        @QueryMap query: Map<String, String>,
        @Header(AUTHORIZATION) authorization: String,
    ): Response<ResponseBody>

    @FormUrlEncoded
    @PATCH(ENTRY)
    suspend fun patch(
        @Path("id") id: Long,
        @FieldMap form: Map<String, String>,
        @Header(AUTHORIZATION) authorization: String,
    ): Response<ResponseBody>

    @DELETE(ENTRY)
    suspend fun delete(
        @Path("id") id: Long,
        @Header(AUTHORIZATION) authorization: String,
    ): Response<ResponseBody>
}
