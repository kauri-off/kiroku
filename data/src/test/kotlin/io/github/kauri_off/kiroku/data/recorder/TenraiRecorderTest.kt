package io.github.kauri_off.kiroku.data.recorder

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

// Section 11.1: the Tenrai recorder asks each title once, a pause apart, and stores status and body, never a header.
class TenraiRecorderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val events = CopyOnWriteArrayList<String>()
    private val into by lazy { File(folder.root, "recorded") }
    private val staging by lazy { File(folder.root, "run") }

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun answers(answer: (RecordedRequest) -> MockResponse) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    events += "${request.method} ${request.url.encodedPath} ${request.headers["User-Agent"]}"
                    return answer(request)
                }
            }
    }

    private fun run(only: String = ""): Int =
        recordTenrai(
            only = only,
            into = into,
            staging = staging,
            lock = File(folder.root, "cache/tenrai.lock"),
            address = server.url("/v1/"),
            today = LocalDate.of(2026, 11, 17),
            say = {},
        ) { events += "pause" }

    private fun reply(
        code: Int,
        body: String,
    ) = MockResponse
        .Builder()
        .code(code)
        .setHeader("Content-Type", "application/json")
        .setHeader("Cache-Control", "public, max-age=600")
        .setHeader("Set-Cookie", "$COOKIE=1")
        .body(body)
        .build()

    @Test
    fun `each title is asked once, a pause apart, with a generic user agent, and a title it has no record of is stored`() {
        answers { if (it.url.encodedPath.contains("/2/")) reply(404, """{"status":404}""") else reply(200, """{"data":{"scores":[]}}""") }

        assertEquals(0, run())

        val asked = events.filter { it != "pause" }
        assertEquals(TenraiRequests.map { "/v1/anime/${it.id}/statistics" }, asked.map { it.split(' ')[1] })
        assertTrue(asked.all { it.startsWith("GET ") && "okhttp/" in it && "Kiroku" !in it })
        assertEquals(List(TenraiRequests.size - 1) { "pause" }, events.filter { it == "pause" })
        assertEquals("pause", events[1])
        assertTrue(File(into, "unknown.404.json").isFile)
        assertTrue(File(into, "popular.200.json").isFile)
    }

    @Test
    fun `no header is stored but the limit and cache ones in the note`() {
        answers { reply(200, """{"data":{"scores":[]}}""") }

        run()

        val all = into.listFiles()!!.joinToString("\n") { it.readText() }
        assertFalse(COOKIE in all)
        assertTrue(File(into, HEADERS_NOTE).readText().contains("cache-control: public, max-age=600"))
    }

    @Test
    fun `a refusal is stored, stops the run before the next request and replaces nothing`() {
        into.mkdirs()
        File(into, "popular.200.json").writeText("earlier")
        answers {
            if (it.url.encodedPath.contains(
                    "/61987/",
                )
            ) {
                reply(429, """{"status":429}""")
            } else {
                reply(200, """{"data":{"scores":[]}}""")
            }
        }

        assertEquals(1, run())

        assertEquals(2, events.count { it != "pause" })
        assertEquals("earlier", File(into, "popular.200.json").readText())
        assertTrue(File(staging, "airing.429.json").isFile)
    }

    @Test
    fun `a run of some requests replaces only their answers`() {
        answers { reply(200, """{"data":{"scores":[]}}""") }
        into.mkdirs()
        File(into, "airing.200.json").writeText("earlier")
        File(into, "popular.200.json").writeText("earlier")

        assertEquals(0, run(only = "popular"))

        assertEquals("earlier", File(into, "airing.200.json").readText())
        assertEquals("{\n    \"data\": {\n        \"scores\": []\n    }\n}\n", File(into, "popular.200.json").readText())
    }

    private companion object {
        const val COOKIE = "session-cookie-41d"
    }
}
