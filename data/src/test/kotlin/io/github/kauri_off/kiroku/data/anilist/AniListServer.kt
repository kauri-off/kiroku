package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.core.airing.FetchedAiring
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.data.anime.recordedTitle
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.recorder.AniListRequests
import io.github.kauri_off.kiroku.data.recorder.LIMIT_NOTE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.rules.ExternalResource
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

// What one request asks: its MAL ids, its page of titles and its page of each title's episodes.
internal data class Asked(
    val ids: List<Int>,
    val page: Int,
    val schedulePage: Int,
) {
    companion object {
        fun of(request: RecordedRequest): Asked {
            val variables = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["variables"]!!.jsonObject
            return Asked(
                variables["ids"]!!.jsonArray.map { it.jsonPrimitive.int },
                variables["page"]!!.jsonPrimitive.int,
                variables["schedulePage"]!!.jsonPrimitive.int,
            )
        }
    }
}

// M3-01's answers, each under the request the recorder sent for it.
internal object AniListRecordings {
    // The day of the recording, at noon in UTC: the moment the cases are read at.
    val RecordedAt: Instant = Instant.parse("2026-11-16T12:00:00Z")

    private val directory = File(checkNotNull(javaClass.getResource("/anilist/recorded")).toURI())

    fun body(file: String): String = File(directory, "$file.200.json").readText()

    fun json(file: String): JsonObject = Json.parseToJsonElement(body(file)).jsonObject

    // The limit AniList reported with its answers, as the recorder noted it.
    val limit: Int =
        File(directory, LIMIT_NOTE)
            .readText()
            .substringAfterLast(':')
            .trim()
            .toInt()

    // The recorder's walk through each case's pages: the next title page first, then for one id the next episode page.
    val byRequest: Map<Asked, String> =
        buildMap {
            for (ask in AniListRequests) {
                var page = 1
                var schedulePage = 1
                var number = 1
                while (true) {
                    val file = if (number == 1) ask.name else "${ask.name}_$number"
                    put(Asked(ask.ids.sorted(), page, schedulePage), file)
                    val answer = json(file)
                    when {
                        answer.more("Page") -> page++
                        ask.ids.size == 1 && answer.more("airingSchedule") -> schedulePage++
                        else -> break
                    }
                    number++
                }
            }
        }

    fun idsOf(case: String): List<Int> = AniListRequests.single { it.name == case }.ids

    // The pages of a case, in the recorder's order.
    fun pagesOf(case: String): List<String> = byRequest.values.filter { it == case || it.startsWith("${case}_") }.sorted()

    private fun JsonElement.more(field: String): Boolean =
        when (this) {
            is JsonObject ->
                entries.any { (key, value) ->
                    val next = ((value as? JsonObject)?.get("pageInfo") as? JsonObject)?.get("hasNextPage") as? JsonPrimitive
                    (key == field && next?.booleanOrNull == true) || value.more(field)
                }
            is JsonArray -> any { it.more(field) }
            else -> false
        }
}

// A title MAL's recorded page names, under [id]: the client sends nothing of a title but its id.
internal fun titleWithId(id: Int): Anime = recordedTitle("airing_weekly").anime.copy(id = AnimeId(id.toLong()))

internal fun Map<AnimeId, Outcome<FetchedAiring?>>.of(id: Int): Outcome<FetchedAiring?> = getValue(AnimeId(id.toLong()))

// A local AniList beside the network fixture's MAL; answers M3-01's recordings unless a test answers otherwise.
@OptIn(ExperimentalCoroutinesApi::class)
internal class AniListServer(
    private val network: NetworkFixture,
) : ExternalResource() {
    val server = MockWebServer()

    // Each request with the virtual time it arrived at, in milliseconds.
    val requests = CopyOnWriteArrayList<Pair<RecordedRequest, Long>>()
    val unrecorded = CopyOnWriteArrayList<Asked>()

    var answer: (Asked) -> MockResponse = ::recorded

    private lateinit var scope: TestScope
    private var client: AniListAiring? = null

    // The test's one client, on its virtual time.
    fun client(test: TestScope): AniListAiring =
        client ?: AniListAiring(network.http, network.clock, server.url("/").toString(), test.testScheduler.timeSource).also {
            scope = test
            client = it
        }

    fun asked(): List<Asked> = requests.map { Asked.of(it.first) }

    fun recorded(asked: Asked): MockResponse {
        val file = AniListRecordings.byRequest[asked.copy(ids = asked.ids.sorted())]
        if (file == null) {
            unrecorded += asked
            return reply(500, "")
        }
        return reply(200, AniListRecordings.body(file))
    }

    override fun before() {
        network.clock.now = AniListRecordings.RecordedAt
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request to scope.testScheduler.currentTime
                    return answer(Asked.of(request))
                }
            }
        server.start()
    }

    override fun after() = server.close()

    companion object {
        fun reply(
            code: Int,
            body: String,
            limit: Int? = AniListRecordings.limit,
            headers: Map<String, String> = emptyMap(),
        ): MockResponse =
            MockResponse
                .Builder()
                .code(code)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .apply { if (limit != null) setHeader("X-RateLimit-Limit", limit.toString()) }
                .apply { headers.forEach { (name, value) -> setHeader(name, value) } }
                .body(body)
                .build()
    }
}
