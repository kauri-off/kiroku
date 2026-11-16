package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.data.anilist.AniListSchedule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Buffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

// An answer of the sandbox: its status, its body and the headers it carries beyond the body's type.
class SandboxAnswer(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

// AniList on the device: the shared query answered from [airing] as AniList answers it, its paging and its limit included.
class SandboxAniList(
    private val airing: () -> SandboxAiring,
    private val clock: Clock,
) {
    // The MAL ids of every query it answered, one list a request.
    val asked = CopyOnWriteArrayList<List<Long>>()

    private val sent = ArrayDeque<Instant>()

    fun answers(url: HttpUrl): Boolean = url == Address

    fun answer(request: Request): SandboxAnswer {
        if (request.header("User-Agent").isNullOrBlank()) return refused(FORBIDDEN, "Forbidden.")
        limited()?.let { return it }
        val variables = variables(request) ?: return refused(BAD_REQUEST, "Only the shared query is answered.")
        val ids = variables.ids
        asked.add(ids)
        val all = ids.toSet().flatMap { airing().entries(it) }.sortedBy { it.aniListId }
        val titles = all.drop((variables.page - 1) * AniListSchedule.TITLES_PER_PAGE).take(AniListSchedule.TITLES_PER_PAGE)
        val body =
            buildJsonObject {
                putJsonObject("data") {
                    putJsonObject("Page") {
                        putJsonObject("pageInfo") { put("hasNextPage", variables.page * AniListSchedule.TITLES_PER_PAGE < all.size) }
                        put("media", JsonArray(titles.map { media(it, variables.schedulePage) }))
                    }
                }
            }
        return SandboxAnswer(OK, body.toString(), limit())
    }

    private fun media(
        entry: AniListEntry,
        page: Int,
    ): JsonObject {
        val nodes = entry.schedule.drop((page - 1) * AniListSchedule.EPISODES_PER_PAGE).take(AniListSchedule.EPISODES_PER_PAGE)
        return buildJsonObject {
            put("idMal", entry.idMal)
            put("episodes", entry.episodes?.let(::JsonPrimitive) ?: JsonNull)
            put("status", entry.status)
            putJsonObject("airingSchedule") {
                putJsonObject("pageInfo") { put("hasNextPage", page * AniListSchedule.EPISODES_PER_PAGE < entry.schedule.size) }
                put(
                    "nodes",
                    buildJsonArray {
                        for (node in nodes) {
                            add(
                                buildJsonObject {
                                    put("episode", node.episode)
                                    put("airingAt", node.at.epochSecond)
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    private class Variables(
        val ids: List<Long>,
        val page: Int,
        val schedulePage: Int,
    )

    // The shared query's variables; null for another query or a body that cannot be read.
    private fun variables(request: Request): Variables? {
        if (request.method != "POST") return null
        val body =
            runCatching {
                json.parseToJsonElement(
                    Buffer().also { request.body?.writeTo(it) }.readUtf8(),
                )
            }.getOrNull() as? JsonObject
        if ((body?.get("query") as? JsonPrimitive)?.contentOrNull != AniListSchedule.QUERY) return null
        val variables = body["variables"] as? JsonObject ?: return null
        val ids = (variables["ids"] as? JsonArray)?.map { (it as? JsonPrimitive)?.longOrNull ?: return null } ?: return null

        fun page(name: String) = ((variables[name] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtLeast(1)
        return Variables(ids, page("page"), page("schedulePage"))
    }

    // AniList's limit a minute: a request over it is refused until the oldest of the minute is a minute old.
    private fun limited(): SandboxAnswer? =
        synchronized(sent) {
            val now = clock.instant()
            while (sent.isNotEmpty() && !sent.first().plus(MINUTE).isAfter(now)) sent.removeFirst()
            if (sent.size < LIMIT) {
                sent.addLast(now)
                return null
            }
            val wait = ((Duration.between(now, sent.first().plus(MINUTE)).toMillis() + 999) / 1000).coerceAtLeast(1)
            refused(TOO_MANY, "Too Many Requests.", limit() + ("Retry-After" to "$wait"))
        }

    private fun limit(): Map<String, String> =
        synchronized(sent) { mapOf("X-RateLimit-Limit" to "$LIMIT", "X-RateLimit-Remaining" to "${LIMIT - sent.size}") }

    private fun refused(
        status: Int,
        message: String,
        headers: Map<String, String> = emptyMap(),
    ) = SandboxAnswer(
        status,
        buildJsonObject {
            put("data", JsonNull)
            put(
                "errors",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("message", message)
                            put("status", status)
                        },
                    )
                },
            )
        }.toString(),
        headers,
    )

    companion object {
        val Address = AniListSchedule.ADDRESS.toHttpUrl()

        // The limit AniList reported when its answers were recorded (anilist/recorded/limit.txt).
        const val LIMIT = 30
        private val MINUTE = Duration.ofMinutes(1)
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val FORBIDDEN = 403
        private const val TOO_MANY = 429
        private val json = Json
    }
}
