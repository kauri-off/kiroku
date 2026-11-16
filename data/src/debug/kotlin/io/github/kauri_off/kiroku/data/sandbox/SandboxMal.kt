package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.data.network.MalHosts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class SandboxWrites { Work, Refused, Fail }

// MAL on the device: the HTTP client's last step, answering every request from [list], or [aniList]'s, and passing none on.
class SandboxMal(
    private val list: MalList,
    private val tokens: SandboxTokens,
    private val aniList: SandboxAniList? = null,
) : Interceptor {
    // No request gets an answer, as with no connection.
    @Volatile
    var offline = false

    // Every token is rejected, a refresh's included: the session ends as when MAL revokes a sign-in.
    @Volatile
    var rejecting = false

    @Volatile
    var delayMillis = 0L

    @Volatile
    var writes = SandboxWrites.Work

    // The access tokens it granted expire now, as when their lifetime ends: the next request is rejected once.
    fun expireAccess() = tokens.expireAccess()

    // Every request it answered, as method, host and path.
    val asked = CopyOnWriteArrayList<String>()

    private class Asked(
        val clientId: String,
        val redirectUri: String,
        val challenge: String,
    )

    private val codes = ConcurrentHashMap<String, Asked>()

    // MAL's page allowing a sign-in at once: the redirect it leads to, or null for an address it cannot answer.
    fun authorize(address: String): String? {
        val url = address.toHttpUrlOrNull()?.takeIf { it.isSandbox && it.encodedPath == SIGN_IN_PATH + "authorize" } ?: return null
        val redirect = url.queryParameter("redirect_uri") ?: return null
        val asked =
            Asked(
                clientId = url.queryParameter("client_id")?.takeIf { it.isNotEmpty() } ?: return null,
                redirectUri = redirect,
                challenge =
                    url.queryParameter("code_challenge")?.takeIf { url.queryParameter("code_challenge_method") == "plain" } ?: return null,
            )
        val state = url.queryParameter("state") ?: return null
        if (url.queryParameter("response_type") != "code") return null
        val code = UUID.randomUUID().toString()
        codes[code] = asked
        return "$redirect?code=${encoded(code)}&state=${encoded(state)}"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        asked += "${request.method} ${request.url.host}${request.url.encodedPath}"
        if (offline) throw UnknownHostException(request.url.host)
        if (delayMillis > 0) Thread.sleep(delayMillis)
        val answer =
            aniList?.takeIf { it.answers(request.url) }?.answer(chain) ?: answer(request).let { SandboxAnswer(it.first, it.second) }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(answer.status)
            .message("")
            .apply { answer.headers.forEach { (name, value) -> header(name, value) } }
            .body(answer.body.toResponseBody(JSON))
            .build()
    }

    private fun answer(request: Request): Pair<Int, String> {
        val url = request.url
        if (!url.isSandbox) return NOT_FOUND
        if (url.encodedPath == SIGN_IN_PATH + "token" && request.method == "POST") return token(form(request))
        val path = url.encodedPath.removePrefix(API_PATH).takeIf { url.encodedPath.startsWith(API_PATH) } ?: return NOT_FOUND
        val bearer = request.header("Authorization")?.removePrefix("Bearer ")
        val signedIn = bearer != null && !rejecting && tokens.accepts(bearer)
        if (bearer != null && !signedIn) return INVALID_TOKEN
        val parts = path.split('/')
        val fields = fieldsOf(url.queryParameter("fields"))
        return when {
            parts.size == 3 && parts[0] == "anime" && parts[2] == "my_list_status" -> {
                if (!signedIn) return INVALID_TOKEN
                val id = parts[1].toLongOrNull() ?: return NOT_FOUND
                when (request.method) {
                    "PATCH" -> changed(id, form(request))
                    "DELETE" -> removed(id)
                    else -> NOT_FOUND
                }
            }
            request.method != "GET" -> NOT_FOUND
            // The client id goes on in the network's step, after the sandbox: a page without a token is the client id's.
            parts.size == 2 && parts[0] == "anime" -> titlePage(parts[1].toLongOrNull() ?: return NOT_FOUND, fields, signedIn)
            path == "users/@me" -> if (signedIn) 200 to profile(fields).toString() else INVALID_TOKEN
            path == "users/@me/animelist" -> if (signedIn) 200 to page(url, fields).toString() else INVALID_TOKEN
            else -> NOT_FOUND
        }
    }

    private fun token(form: Map<String, String>): Pair<Int, String> =
        when (form["grant_type"]) {
            "authorization_code" -> {
                val asked = form["code"]?.let(codes::remove)
                val valid =
                    asked != null &&
                        form["code_verifier"] == asked.challenge &&
                        form["redirect_uri"] == asked.redirectUri &&
                        form["client_id"] == asked.clientId
                if (valid) granted(tokens.granted()) else INVALID_GRANT
            }
            "refresh_token" -> {
                val refresh = form["refresh_token"]
                if (refresh != null && !rejecting && tokens.refreshes(refresh)) granted(tokens.granted(refresh)) else INVALID_REFRESH
            }
            else -> INVALID_GRANT
        }

    // MAL's printed answer to a token request.
    private fun granted(pair: Pair<String, String>): Pair<Int, String> =
        200 to
            buildJsonObject {
                put("token_type", "Bearer")
                put("expires_in", SandboxTokens.ACCESS_LIFETIME.seconds)
                put("access_token", pair.first)
                put("refresh_token", pair.second)
            }.toString()

    private fun changed(
        id: Long,
        form: Map<String, String>,
    ): Pair<Int, String> {
        failedWrite()?.let { return it }
        return when (val written = list.write(id, form)) {
            is Written.Done -> 200 to values(written.entry, null).toString()
            Written.Refused -> BAD_REQUEST
            Written.Unknown -> NOT_FOUND
        }
    }

    // MAL answers a removal with an empty array, also for a title that was not on the list.
    private fun removed(id: Long): Pair<Int, String> {
        failedWrite()?.let { return it }
        return if (list.remove(id)) 200 to "[]" else NOT_FOUND
    }

    private fun failedWrite(): Pair<Int, String>? =
        when (writes) {
            SandboxWrites.Work -> null
            SandboxWrites.Refused -> FORBIDDEN
            SandboxWrites.Fail -> SERVER_ERROR
        }

    private fun titlePage(
        id: Long,
        fields: Map<String, Set<String>?>,
        signedIn: Boolean,
    ): Pair<Int, String> {
        val title = list.title(id) ?: return NOT_FOUND
        val entry = list.entry(id)?.takeIf { signedIn && OWN_ENTRY in fields }
        val page = projected(title, fields) + listOfNotNull(entry?.let { OWN_ENTRY to values(it, fields[OWN_ENTRY]) })
        return 200 to JsonObject(page).toString()
    }

    private fun page(
        url: HttpUrl,
        fields: Map<String, Set<String>?>,
    ): JsonObject {
        val status = url.queryParameter("status")
        val entries = list.entries().filter { status == null || it.second.status == status }
        val limit = (url.queryParameter("limit")?.toIntOrNull() ?: DEFAULT_PAGE).coerceIn(1, minOf(LARGEST_PAGE, list.pageSize))
        val offset = (url.queryParameter("offset")?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val data =
            entries.drop(offset).take(limit).mapNotNull { (id, entry) ->
                val title = list.title(id) ?: return@mapNotNull null
                JsonObject(
                    mapOf("node" to JsonObject(projected(title, fields))) +
                        listOfNotNull(if (LIST_STATUS in fields) LIST_STATUS to values(entry, fields[LIST_STATUS]) else null),
                )
            }

        fun at(offset: Int) =
            JsonPrimitive(
                url
                    .newBuilder()
                    .setQueryParameter("offset", "$offset")
                    .build()
                    .toString(),
            )
        val paging =
            buildMap {
                if (offset > 0) put("previous", at(maxOf(0, offset - limit)))
                if (offset + limit < entries.size) put("next", at(offset + limit))
            }
        return JsonObject(mapOf("data" to JsonArray(data), "paging" to JsonObject(paging)))
    }

    // The sandbox's own user, made up, with statistics counted from the list.
    private fun profile(fields: Map<String, Set<String>?>): JsonObject {
        val entries = list.entries().map { it.second }
        val episodes = entries.sumOf { it.watched }
        val scored = entries.filter { it.score > 0 }

        fun days(status: String) = entries.filter { it.status == status }.sumOf { it.watched } * EPISODE_DAYS
        val statistics =
            buildJsonObject {
                for ((name, status) in STATISTIC_STATUSES) {
                    put("num_items_$name", entries.count { it.status == status })
                    put("num_days_$name", days(status))
                }
                put("num_items", entries.size)
                put("num_days_watched", episodes * EPISODE_DAYS)
                put("num_days", (episodes * EPISODE_DAYS).toInt())
                put("num_episodes", episodes)
                put("num_times_rewatched", 0)
                put("mean_score", if (scored.isEmpty()) 0.0 else scored.sumOf { it.score }.toDouble() / scored.size)
            }
        val all =
            mapOf(
                "id" to JsonPrimitive(USER_ID),
                "name" to JsonPrimitive(USER_NAME),
                "joined_at" to JsonPrimitive(JOINED_AT),
                "anime_statistics" to statistics,
            )
        return JsonObject(all.filterKeys { it in DEFAULT_PROFILE || it in fields })
    }

    private val HttpUrl.isSandbox: Boolean get() = scheme == Api.scheme && host == Api.host && port == Api.port

    companion object {
        // Addresses that reach no host, so a request that missed the sandbox goes nowhere either.
        val Hosts = MalHosts(api = "https://sandbox.invalid/v2/", signIn = "https://sandbox.invalid/v1/oauth2/")
        private val Api = Hosts.api.toHttpUrl()
        const val CLIENT_ID = "sandbox-client"
        const val USER_NAME = "Sandbox_User"
        private const val USER_ID = 1L
        private const val JOINED_AT = "2001-01-01T00:00:00+00:00"
        private const val API_PATH = "/v2/"
        private const val SIGN_IN_PATH = "/v1/oauth2/"
        private const val OWN_ENTRY = "my_list_status"
        private const val LIST_STATUS = "list_status"
        private const val DEFAULT_PAGE = 100
        private const val LARGEST_PAGE = 1000
        private const val EPISODE_DAYS = 24.0 / (24 * 60)
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val DEFAULT_PROFILE = setOf("id", "name", "joined_at")
        private val ALWAYS = setOf("id", "title", "main_picture")
        private val STATISTIC_STATUSES =
            listOf("watching" to "watching", "completed" to "completed", "on_hold" to "on_hold", "dropped" to "dropped")
        private val UPDATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

        // The shapes of MAL's own answers, committed by the client-id recorder or printed in MAL's reference.
        private val NOT_FOUND = 404 to """{"message":"","error":"not_found"}"""
        private val INVALID_TOKEN = 401 to """{"error":"invalid_token"}"""
        private val INVALID_REFRESH =
            401 to """{"error":"invalid_request","message":"The refresh token is invalid.","hint":"Cannot decrypt the refresh token"}"""
        private val INVALID_GRANT = 400 to """{"error":"invalid_grant","message":"The authorization code is invalid."}"""
        private val BAD_REQUEST = 400 to """{"message":"invalid value","error":"bad_request"}"""
        private val FORBIDDEN = 403 to """{"message":"","error":"forbidden"}"""
        private val SERVER_ERROR = 500 to """{"message":"","error":"internal_server_error"}"""

        // A title's fields that were asked, and the three MAL always gives.
        internal fun projected(
            title: JsonObject,
            fields: Map<String, Set<String>?>,
        ): Map<String, JsonElement> = title.filterKeys { it in ALWAYS || it in fields }

        // An entry's values: a write's answer has all of them; a read has those asked, and is_rewatching.
        internal fun values(
            entry: Listed,
            asked: Set<String>?,
        ): JsonObject {
            val all =
                buildMap<String, JsonElement> {
                    put("status", JsonPrimitive(entry.status))
                    put("score", JsonPrimitive(entry.score))
                    put("num_episodes_watched", JsonPrimitive(entry.watched))
                    put("is_rewatching", JsonPrimitive(false))
                    put("updated_at", JsonPrimitive(UPDATED_AT.format(entry.updatedAt.atOffset(ZoneOffset.UTC))))
                    entry.startDate?.let { put("start_date", JsonPrimitive(it)) }
                    entry.finishDate?.let { put("finish_date", JsonPrimitive(it)) }
                    if (asked == null) {
                        put("priority", JsonPrimitive(0))
                        put("num_times_rewatched", JsonPrimitive(0))
                        put("rewatch_value", JsonPrimitive(0))
                        put("tags", JsonArray(emptyList()))
                        put("comments", JsonPrimitive(""))
                    }
                }
            return JsonObject(if (asked == null) all else all.filterKeys { it == "is_rewatching" || it in asked })
        }

        // MAL's fields parameter: each name, with the names inside its braces, or null where it has none.
        internal fun fieldsOf(text: String?): Map<String, Set<String>?> {
            val fields = mutableMapOf<String, Set<String>?>()
            var depth = 0
            var start = 0
            val value = text.orEmpty()
            for (i in 0..value.length) {
                val c = value.getOrNull(i)
                when {
                    c == '{' -> depth++
                    c == '}' -> depth--
                    (c == ',' && depth == 0) || c == null -> {
                        val field = value.substring(start, i).trim()
                        if (field.isNotEmpty()) {
                            val inner = field.substringAfter('{', "").substringBeforeLast('}', "")
                            fields[field.substringBefore('{')] = if ('{' in field) fieldsOf(inner).keys else null
                        }
                        start = i + 1
                    }
                }
            }
            return fields
        }

        private fun form(request: Request): Map<String, String> {
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            return body.split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
        }

        private fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
