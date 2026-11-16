package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.anilist.AniListSchedule
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

// Section 11.1: the AniList recorder asks the shared query, pages as AniList says, and stores only what was asked.
class AniListRecorderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val said = mutableListOf<String>()
    private val events = CopyOnWriteArrayList<String>()
    private val into by lazy { File(folder.root, "recorded") }
    private val staging by lazy { File(folder.root, "run") }
    private val lock by lazy { File(folder.root, "cache/anilist.lock") }

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun answers(answer: (RecordedRequest, Int, Int) -> MockResponse) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val variables = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["variables"]!!.jsonObject
                    events += "ask ${variables["ids"]!!.jsonArray.size} ${variables["page"]} ${variables["schedulePage"]}"
                    return answer(request, variables["page"]!!.jsonPrimitive.int, variables["schedulePage"]!!.jsonPrimitive.int)
                }
            }
    }

    private fun run(only: String = ""): Int =
        recordAniList(
            only = only,
            into = into,
            staging = staging,
            lock = lock,
            address = server.url("/"),
            today = LocalDate.of(2026, 11, 16),
            say = said::add,
        ) { events += "pause" }

    private fun reply(
        body: String,
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .setHeader("Content-Type", "application/json")
        .setHeader("X-RateLimit-Limit", "30")
        .setHeader("X-RateLimit-Remaining", "29")
        .setHeader("Set-Cookie", "$COOKIE=1")
        .body(body)
        .build()

    private fun titles(
        morePages: Boolean,
        moreEpisodes: Boolean,
        extra: String = "",
    ) = """{"data":{"Page":{"pageInfo":{"hasNextPage":$morePages},"media":[{"idMal":1,"episodes":12,"status":"RELEASING",$extra""" +
        """"airingSchedule":{"pageInfo":{"hasNextPage":$moreEpisodes},"nodes":[{"episode":1,"airingAt":1800000000}]}}]}}}"""

    private fun stored() =
        into
            .listFiles()
            .orEmpty()
            .map { it.name }
            .sorted()

    @Test
    fun `every request carries the shared query and a user agent, one at a time with a pause between`() {
        answers { request, _, _ ->
            assertEquals(
                AniListSchedule.QUERY,
                Json
                    .parseToJsonElement(request.body!!.utf8())
                    .jsonObject["query"]!!
                    .jsonPrimitive.content,
            )
            assertTrue(request.headers["User-Agent"].orEmpty().isNotBlank())
            reply(titles(morePages = false, moreEpisodes = false))
        }

        assertEquals(0, run("airing_weekly,on_a_break"))

        assertEquals(listOf("ask 1 1 1", "pause", "ask 1 1 1"), events)
    }

    @Test
    fun `a request for many titles is followed through AniList's pages of titles`() {
        answers { _, page, _ -> reply(titles(morePages = page < 2, moreEpisodes = true)) }

        assertEquals(0, run("more_than_one_page"))

        assertEquals(listOf("ask 60 1 1", "pause", "ask 60 2 1"), events)
        assertEquals(listOf(LIMIT_NOTE, "more_than_one_page.200.json", "more_than_one_page_2.200.json").sorted(), stored())
    }

    @Test
    fun `a request for one title is followed through its pages of episodes`() {
        answers { _, _, schedulePage -> reply(titles(morePages = false, moreEpisodes = schedulePage < 3)) }

        assertEquals(0, run("hundreds_of_episodes"))

        assertEquals(listOf("ask 1 1 1", "pause", "ask 1 1 2", "pause", "ask 1 1 3"), events)
        assertEquals(
            listOf(
                LIMIT_NOTE,
                "hundreds_of_episodes.200.json",
                "hundreds_of_episodes_2.200.json",
                "hundreds_of_episodes_3.200.json",
            ).sorted(),
            stored(),
        )
    }

    @Test
    fun `no header is stored, and the limit is noted without its header`() {
        answers { _, _, _ -> reply(titles(morePages = false, moreEpisodes = false)) }

        assertEquals(0, run("airing_weekly"))

        val texts = into.listFiles()!!.associate { it.name to it.readText() }
        for ((name, text) in texts) {
            assertFalse(name, text.contains(COOKIE) || text.contains("RateLimit") || text.contains("29"))
        }
        assertEquals("Requests a minute AniList reported with its answers of 2026-11-16: 30\n", texts[LIMIT_NOTE])
    }

    @Test
    fun `an answer holding what was not asked stops the run and replaces nothing`() {
        File(into, "airing_weekly.200.json").apply { parentFile.mkdirs() }.writeText("earlier")
        answers { _, _, _ -> reply(titles(morePages = false, moreEpisodes = false, extra = """"description":"Somebody's prose",""")) }

        assertEquals(1, run("airing_weekly"))

        assertEquals(listOf("airing_weekly.200.json"), stored())
        assertEquals("earlier", File(into, "airing_weekly.200.json").readText())
        assertTrue(said.joinToString("\n"), said.any { "/data/Page/media/description" in it })
    }

    @Test
    fun `an error AniList puts beside its data is not asked, and stops the run`() {
        answers { _, _, _ -> reply("""{"data":null,"errors":[{"message":"Not Found.","status":404}]}""") }

        assertEquals(1, run("airing_weekly"))

        assertEquals(emptyList<String>(), stored())
    }

    @Test
    fun `a refusal stops the run before the next request and replaces nothing`() {
        answers { _, _, _ -> reply("""{"data":null,"errors":[{"message":"Too Many Requests.","status":429}]}""", code = 429) }

        assertEquals(1, run("airing_weekly,on_a_break"))

        assertEquals(listOf("ask 1 1 1"), events)
        assertEquals(emptyList<String>(), stored())
    }

    @Test
    fun `a request that keeps saying there is more stops the run`() {
        answers { _, _, _ -> reply(titles(morePages = false, moreEpisodes = true)) }

        assertEquals(1, run("airing_weekly"))

        assertEquals(ANILIST_MOST_PAGES, events.count { it.startsWith("ask") })
        assertEquals(emptyList<String>(), stored())
    }

    @Test
    fun `a new run takes the place of every earlier page of its requests and of nothing else`() {
        into.mkdirs()
        listOf("hundreds_of_episodes.200.json", "hundreds_of_episodes_2.200.json", "hundreds_of_episodes_7.200.json", "on_a_break.200.json")
            .forEach { File(into, it).writeText("earlier") }
        answers { _, _, schedulePage -> reply(titles(morePages = false, moreEpisodes = schedulePage < 2)) }

        assertEquals(0, run("hundreds_of_episodes"))

        assertEquals(
            listOf(LIMIT_NOTE, "hundreds_of_episodes.200.json", "hundreds_of_episodes_2.200.json", "on_a_break.200.json").sorted(),
            stored(),
        )
        assertEquals("earlier", File(into, "on_a_break.200.json").readText())
        assertTrue(File(into, "hundreds_of_episodes_2.200.json").readText().contains("airingSchedule"))
    }

    @Test
    fun `a name that is not on the list sends nothing`() {
        answers { _, _, _ -> reply(titles(morePages = false, moreEpisodes = false)) }

        assertEquals(1, run("airing_weekly,no_such_case"))

        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val COOKIE = "planted-cookie"
    }
}
