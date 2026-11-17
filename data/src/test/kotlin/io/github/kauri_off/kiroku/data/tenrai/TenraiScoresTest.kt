package io.github.kauri_off.kiroku.data.tenrai

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.score.ScoreDistribution
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.dropped
import io.github.kauri_off.kiroku.data.recorder.TenraiRequests
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// The client against a local Tenrai that answers the recordings: what the port obliges, the pace, a refusal.
@OptIn(ExperimentalCoroutinesApi::class)
class TenraiScoresTest {
    private val network = NetworkFixture()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(network)

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<Pair<RecordedRequest, Long>>()
    private lateinit var scope: TestScope
    private var answer: (Long) -> MockResponse = ::recorded
    private val blockFile by lazy { File(folder.root, "tenrai.block") }

    @Before
    fun start() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request to scope.testScheduler.currentTime
                    val id = request.url.pathSegments.let { if (it.size == 4 && it[3] == "statistics") it[2].toLong() else -1 }
                    return answer(id)
                }
            }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun TestScope.client(): TenraiScores {
        scope = this
        return TenraiScores(network.http, network.clock, blockFile, server.url("/v1/").toString(), testScheduler.timeSource)
    }

    private fun recorded(id: Long): MockResponse {
        val case = TenraiRequests.singleOrNull { it.id == id } ?: return answer(500, "")
        val file = recordedFiles().single { it.name.substringBefore('.') == case.name }
        return answer(file.name.split('.')[1].toInt(), file.readText())
    }

    private fun gaps(): List<Long> = requests.map { it.second }.zipWithNext { a, b -> b - a }

    @Test
    fun `each recorded title is its votes per score as Tenrai gave them, and a title it has no record of is null`() =
        runTest {
            val client = client()
            for (ask in TenraiRequests) {
                val file = recordedFiles().single { it.name.substringBefore('.') == ask.name }
                val expected = if (file.name.contains(".404.")) null else distributionIn(file.readText())

                assertEquals(ask.name, Outcome.Success(expected), client.distribution(AnimeId(ask.id)))
            }
            val popular = (client.distribution(AnimeId(1)) as Outcome.Success).value!!
            assertEquals(373_989, popular[10])
            assertEquals(2_500, popular[1])
            assertTrue((client.distribution(AnimeId(59068)) as Outcome.Success).value!!.total in 1..9)
            assertEquals(Outcome.Success(null), client.distribution(AnimeId(2)))
        }

    @Test
    fun `a title nobody scored, as all zeros or as no scores at all, is a distribution with no votes`() =
        runTest {
            val recordedBody = recordedBody("not_yet_aired")
            answer = { answer(200, withScores(recordedBody) { scores -> JsonArray(scores.map { it.withVotes(0) }) }) }
            assertEquals(Outcome.Success(ScoreDistribution(emptyMap())), client().distribution(AnimeId(59068)))

            answer = { answer(200, withScores(recordedBody) { JsonArray(emptyList()) }) }
            assertEquals(Outcome.Success(ScoreDistribution(emptyMap())), client().distribution(AnimeId(59068)))
            assertEquals(emptyList<Throwable>(), network.unexpected)
        }

    @Test
    fun `scores in another order or with some left out are read, a score left out having none`() =
        runTest {
            val body = recordedBody("few_votes")
            answer =
                {
                    answer(
                        200,
                        withScores(body) { scores ->
                            JsonArray(
                                scores.reversed().filter {
                                    it.jsonObject["score"]!!.jsonPrimitive.int >
                                        2
                                },
                            )
                        },
                    )
                }
            val read = (client().distribution(AnimeId(65189)) as Outcome.Success).value!!

            assertEquals(0, read[1])
            assertEquals(0, read[2])
            assertEquals(distributionIn(body)[10], read[10])
        }

    @Test
    fun `an answer that cannot be read is a failure and is reported without a value, and a page that is not JSON is not reported`() =
        runTest {
            val body = recordedBody("few_votes")
            val unreadable =
                listOf(
                    withScores(body) { scores -> JsonArray(scores + scores.first()) },
                    withScores(body) { scores -> JsonArray(scores.map { it.withVotes(-1) }) },
                    withScores(
                        body,
                    ) { scores -> JsonArray(listOf(JsonObject(scores.first().jsonObject + ("score" to JsonPrimitive(11))))) },
                    withScores(
                        body,
                    ) { scores -> JsonArray(listOf(JsonObject(scores.first().jsonObject + ("votes" to JsonPrimitive("12"))))) },
                    """{"data":{}}""",
                )
            for (each in unreadable) {
                network.unexpected.clear()
                answer = { answer(200, each) }
                assertEquals(each, Outcome.Failure(DataError.ServerError), client().distribution(AnimeId(65189)))
                assertTrue(each, network.unexpected.single() is TenraiOddAnswer)
                assertTrue(each, network.unexpected.single().message in setOf("scores", "score"))
            }
            network.unexpected.clear()
            answer = { answer(200, "<html>Sign in to the network</html>", "text/html") }
            assertEquals(Outcome.Failure(DataError.ServerError), client().distribution(AnimeId(65189)))
            assertEquals(emptyList<Throwable>(), network.unexpected)
        }

    @Test
    fun `a 404 that is not Tenrai's no record, a failure of the server and no answer are failures, never null`() =
        runTest {
            val client = client()
            answer = { answer(404, """{"status":404,"type":"NotFoundException","message":"","error":"","path":""}""") }
            assertEquals(Outcome.Failure(DataError.ServerError), client.distribution(AnimeId(1)))
            assertTrue(network.unexpected.single() is TenraiOddAnswer)

            answer = { answer(503, "") }
            assertEquals(Outcome.Failure(DataError.ServerError), client.distribution(AnimeId(1)))
            answer = { dropped() }
            assertEquals(Outcome.Failure(DataError.ServerError), client.distribution(AnimeId(1)))
            network.online = false
            assertEquals(Outcome.Failure(DataError.NoConnection), client.distribution(AnimeId(1)))
            // OkHttp may send a dropped read again at once; each call still waits out the failure before it.
            assertTrue(requests.last().second - requests.first().second >= 3 * TenraiScores.AfterFailure.inWholeMilliseconds)
        }

    @Test
    fun `a request carries the title's MAL id and the app's User-Agent, nothing of an account`() =
        runTest {
            client().distribution(AnimeId(61987))
            val request = requests.single().first

            assertEquals("GET", request.method)
            assertEquals("/v1/anime/61987/statistics", request.url.encodedPath)
            assertEquals(null, request.url.query)
            assertEquals(
                setOf("host", "connection", "accept-encoding", "user-agent", "accept"),
                request.headers
                    .names()
                    .map { it.lowercase() }
                    .toSet(),
            )
            assertEquals("Kiroku/1.2.3", request.headers["User-Agent"])
        }

    @Test
    fun `requests keep under Tenrai's 120 a minute, half a second apart at least, also when asked at once`() =
        runTest {
            val client = client()
            repeat(3) { client.distribution(AnimeId(1)) }
            val others = List(3) { async { client.distribution(AnimeId(61987)) } }
            others.forEach { it.await() }

            assertEquals(6, requests.size)
            assertTrue(gaps().toString(), gaps().all { it >= TENRAI_GAP_MILLIS })
        }

    @Test
    fun `a refusal holds every request back, for its Retry-After or a minute, also in a new process, and an answer ends it`() =
        runTest {
            val client = client()
            answer = {
                MockResponse
                    .Builder()
                    .code(429)
                    .setHeader("Retry-After", "120")
                    .body("{}")
                    .build()
            }
            assertEquals(Outcome.Failure(DataError.Refused), client.distribution(AnimeId(1)))
            answer = ::recorded

            assertEquals(Outcome.Failure(DataError.Refused), client.distribution(AnimeId(1)))
            assertEquals(Outcome.Failure(DataError.Refused), client().distribution(AnimeId(61987)))
            assertEquals(1, requests.size)
            assertTrue(blockFile.exists())

            advanceTimeBy(2.minutes + 1.milliseconds)
            network.clock.now = network.clock.now.plusSeconds(121)
            assertTrue(client.distribution(AnimeId(1)) is Outcome.Success)
            assertTrue(!blockFile.exists())

            answer = { answer(403, "") }
            assertEquals(Outcome.Failure(DataError.Refused), client.distribution(AnimeId(1)))
            advanceTimeBy(TenraiScores.FirstRefusal - 1.seconds)
            assertEquals(Outcome.Failure(DataError.Refused), client.distribution(AnimeId(1)))
            assertEquals(3, requests.size)
        }

    @Test
    fun `a refusal leaves MAL's back-off alone`() =
        runTest {
            answer = { answer(429, "{}") }
            client().distribution(AnimeId(1))

            assertEquals(java.time.Duration.ZERO, network.http.refusedFor())
        }

    private companion object {
        // Tenrai's documented 120 requests a minute, read as one each half second.
        const val TENRAI_GAP_MILLIS = 500L

        fun recordedFiles(): List<File> =
            File(checkNotNull(TenraiScoresTest::class.java.getResource("/tenrai/recorded")).toURI())
                .listFiles()
                .orEmpty()
                .filter { it.name.endsWith(".json") }

        fun recordedBody(name: String): String = recordedFiles().single { it.name.substringBefore('.') == name }.readText()

        fun distributionIn(body: String): ScoreDistribution =
            ScoreDistribution(
                Json
                    .parseToJsonElement(body)
                    .jsonObject["data"]!!
                    .jsonObject["scores"]!!
                    .jsonArray
                    .associate { it.jsonObject["score"]!!.jsonPrimitive.int to it.jsonObject["votes"]!!.jsonPrimitive.int },
            )

        fun withScores(
            body: String,
            change: (JsonArray) -> JsonArray,
        ): String {
            val answer = Json.parseToJsonElement(body).jsonObject
            val data = answer["data"]!!.jsonObject
            return JsonObject(answer + ("data" to JsonObject(data + ("scores" to change(data["scores"]!!.jsonArray))))).toString()
        }

        fun kotlinx.serialization.json.JsonElement.withVotes(votes: Int) = JsonObject(jsonObject + ("votes" to JsonPrimitive(votes)))
    }
}
