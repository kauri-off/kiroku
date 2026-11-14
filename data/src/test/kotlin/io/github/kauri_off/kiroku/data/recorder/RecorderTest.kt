package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.CLIENT_ID_HEADER
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.dropped
import io.github.kauri_off.kiroku.data.network.received
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// Section 11.1: the recorder uses the client id and the user names, and stores and prints neither, nor a header.
class RecorderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val api = MockWebServer()
    private val signIn = MockWebServer()
    private val said = mutableListOf<String>()
    private val events = CopyOnWriteArrayList<String>()
    private val into by lazy { File(folder.root, "recorded") }
    private val staging by lazy { File(folder.root, "run") }
    private val lock by lazy { File(folder.root, "cache/recorder.lock") }
    private val hosts by lazy { MalHosts(api = api.url("/v2/").toString(), signIn = signIn.url("/v1/oauth2/").toString()) }

    @Before
    fun start() {
        api.start()
        signIn.start()
    }

    @After
    fun stop() {
        api.close()
        signIn.close()
    }

    private fun recorder(
        clientId: String = CLIENT_ID,
        private: String = PRIVATE,
    ) = Recorder(
        http = recorderHttp(hosts, clientId),
        users = mapOf(RecordedUser.Public to PUBLIC, RecordedUser.Private to private),
        into = into,
        staging = staging,
        say = said::add,
        pause = { events += "pause" },
    )

    // The build's task with [environment]; [hosts] may be given broken.
    private fun task(
        environment: Map<String, String>,
        hosts: MalHosts = this.hosts,
    ): Int = recordMal(environment, into, staging, lock, hosts, said::add) { events += "pause" }

    private fun MockWebServer.answers(answer: (RecordedRequest) -> MockResponse) {
        dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    events += request.url.encodedPath
                    return answer(request)
                }
            }
    }

    private fun File.texts(): Map<String, String> = listFiles().orEmpty().associate { it.name to it.readText() }

    private val stored: Map<String, String> get() = into.texts()

    private val asked: List<String> get() = events.filter { it != "pause" }

    @Test
    fun `what is stored and said holds no client id, user name, header or query value`() {
        val repeating =
            MockResponse
                .Builder()
                .addHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "planted=cookie")
                .addHeader("X-Planted", "planted-header")
                .body(
                    """{"echo":"$CLIENT_ID, ${PUBLIC.uppercase()}","paging":{"next":"https://mal.example/v2/users/$PUBLIC/animelist?offset=10"}}""",
                ).build()
        api.answers { repeating }
        signIn.answers { repeating }

        recorder().record(
            listOf(
                Ask("title", "anime/1?fields=one,two"),
                Ask("list", "users/${RecordedUser.Public.standIn}/animelist?fields=one&limit=10"),
                Ask("as_user", "users/@me", accessToken = TOKEN),
                Ask("refresh", "token", refreshToken = REFRESH),
            ),
        )

        val title = api.received()
        assertEquals("/v2/anime/1?fields=one,two", title.target)
        assertEquals(CLIENT_ID, title.headers[CLIENT_ID_HEADER])
        val list = api.received()
        assertEquals("/v2/users/$PUBLIC/animelist?fields=one&limit=10", list.target)
        assertEquals(CLIENT_ID, list.headers[CLIENT_ID_HEADER])
        val asUser = api.received()
        assertEquals("Bearer $TOKEN", asUser.headers[AUTHORIZATION])
        assertNull(asUser.headers[CLIENT_ID_HEADER])
        val refresh = signIn.received()
        assertEquals("POST /v1/oauth2/token", "${refresh.method} ${refresh.target}")
        assertEquals("client_id=$CLIENT_ID&grant_type=refresh_token&refresh_token=$REFRESH", refresh.body!!.utf8())

        assertEquals(setOf("title.200.json", "list.200.json", "as_user.200.json", "refresh.200.json"), stored.keys)
        assertTrue(stored.values.all { "users/public-user/animelist?offset=10" in it && "client-id, public-user" in it })
        val written = (stored.keys + stored.values + said).joinToString("\n")
        for (secret in listOf(CLIENT_ID, PUBLIC, TOKEN, REFRESH, "planted", CLIENT_ID_HEADER, AUTHORIZATION, "Bearer", "Cookie")) {
            assertFalse(secret, written.contains(secret, ignoreCase = true))
        }
        assertEquals(4, said.size)
        for (ofTheQuery in listOf("fields", "one", "limit")) assertFalse(said.any { ofTheQuery in it })
        assertFalse(staging.exists())
    }

    @Test
    fun `of a body that is not JSON only the status and the content type are stored`() {
        api.answers { answer(503, body = "<html>Some%5FName&#45;7 $PUBLIC $CLIENT_ID</html>", type = "text/html; charset=UTF-8") }

        recorder().record(listOf(Ask("page", "anime/1")))

        assertEquals(mapOf("page.503.txt" to "503, text/html; charset=UTF-8\n"), stored)
    }

    @Test
    fun `without a client id the recorder stops before any request`() {
        api.answers { answer() }

        val stop = assertThrows(RecorderStop::class.java) { recorder(clientId = " ").record(listOf(Ask("title", "anime/1"))) }

        assertEquals("no client id", stop.message)
        assertEquals(0, api.requestCount)
        assertEquals(emptyMap<String, String>(), stored)
    }

    @Test
    fun `the task has no client id but the one the build passes it, and says so before it looks at the requests`() {
        api.answers { answer() }

        val exit = task(mapOf("MAL_CLIENT_ID" to CLIENT_ID, "KIROKU_RECORD_CLIENT_ID" to " ", "KIROKU_RECORD_ONLY" to "no_such_request"))

        assertEquals(1, exit)
        assertEquals(listOf("Requests sent to MAL: 0", "no client id"), said)
        assertEquals(0, api.requestCount)
    }

    @Test
    fun `of an exception that is not the recorder's own the task says the class and nothing else`() {
        val environment =
            mapOf(
                "KIROKU_RECORD_CLIENT_ID" to CLIENT_ID,
                "KIROKU_RECORD_PUBLIC_USER" to PUBLIC,
                "KIROKU_RECORD_ONLY" to "list_public",
            )

        val exit = task(environment, MalHosts(api = "/", signIn = "/"))

        assertEquals(1, exit)
        assertEquals(listOf("Requests sent to MAL: 0", "The recorder failed: java.lang.IllegalArgumentException"), said)
    }

    @Test
    fun `a second recorder asks nothing while one is running`() {
        api.answers { answer() }
        val environment = mapOf("KIROKU_RECORD_CLIENT_ID" to CLIENT_ID, "KIROKU_RECORD_ONLY" to "title_unknown")

        val second = alone(lock) { task(environment) }
        val afterwards = task(environment)

        assertEquals(1, second)
        assertEquals(listOf("Requests sent to MAL: 0", "another recorder is running, nothing was asked"), said.take(2))
        assertEquals(0, afterwards)
        assertEquals(listOf("/v2/anime/2"), asked)
        assertEquals(setOf("title_unknown.200.json"), stored.keys)
    }

    @Test
    fun `a request for a user whose name is not set is skipped and the others are recorded`() {
        api.answers { answer() }

        recorder(private = "").record(
            listOf(Ask("private", "users/${RecordedUser.Private.standIn}/animelist"), Ask("title", "anime/1")),
        )

        assertEquals(listOf("/v2/anime/1"), events)
        assertEquals(setOf("title.200.json"), stored.keys)
        assertEquals("private: skipped, kiroku.recordPrivateUser is not set", said.first())
    }

    @Test
    fun `requests go one at a time with a pause between them`() {
        api.answers { answer() }
        val recorder = recorder()

        recorder.record(listOf(Ask("first", "anime/1"), Ask("second", "anime/5"), Ask("third", "anime/6")))

        assertEquals(listOf("/v2/anime/1", "pause", "/v2/anime/5", "pause", "/v2/anime/6"), events)
        assertEquals(3, recorder.sent)
    }

    // A list of [length] entries, served as MAL pages it.
    private fun aListOf(length: Int): (RecordedRequest) -> MockResponse =
        { request ->
            val limit = request.url.queryParameter("limit")!!.toInt()
            val offset = request.url.queryParameter("offset")!!.toInt()
            val entries = (offset until minOf(offset + limit, length)).joinToString(",") { """{"node":{"id":$it}}""" }
            val next =
                if (offset + limit <
                    length
                ) {
                    """"next":"https://mal.example/v2/users/$PUBLIC/animelist?offset=${offset + limit}""""
                } else {
                    ""
                }
            answer(body = """{"data":[$entries],"paging":{$next}}""")
        }

    @Test
    fun `a list is read whole in the pages asked for, each stored with ten entries`() {
        val queries = CopyOnWriteArrayList<String>()
        api.answers { request ->
            queries += request.url.query
            aListOf(47)(request)
        }
        val recorder = recorder()

        recorder.record(listOf(Ask("list", "users/${RecordedUser.Public.standIn}/animelist?fields=one&sort=anime_title", pages = 3)))

        assertEquals(
            listOf(
                "sort=anime_title&limit=1000&offset=0",
                "fields=one&sort=anime_title&limit=16&offset=0",
                "fields=one&sort=anime_title&limit=16&offset=16",
                "fields=one&sort=anime_title&limit=16&offset=32",
            ),
            queries,
        )
        assertEquals(4, recorder.sent)
        assertEquals(setOf("list_1.200.json", "list_2.200.json", "list_3.200.json"), stored.keys)
        val ids =
            stored.toSortedMap().values.map { page ->
                Regex(""""id": (\d+)""").findAll(page).map { it.groupValues[1].toInt() }.toList()
            }
        assertEquals(listOf((0..9).toList(), (16..25).toList(), (32..41).toList()), ids)
        assertTrue("users/public-user/animelist?offset=32" in stored.getValue("list_2.200.json"))
        assertFalse("next" in stored.getValue("list_3.200.json"))
    }

    @Test
    fun `a list that cannot be read in the pages asked for is left out and the run goes on`() {
        api.answers { request ->
            when {
                PRIVATE in request.url.encodedPath -> answer(403, body = """{"error":"forbidden"}""")
                "animelist" in request.url.encodedPath -> answer(body = """{"data":[{"node":{"id":1}},{"node":{"id":5}}],"paging":{}}""")
                else -> answer()
            }
        }

        recorder().record(
            listOf(
                Ask("short", "users/${RecordedUser.Public.standIn}/animelist", pages = 3),
                Ask("private", "users/${RecordedUser.Private.standIn}/animelist", pages = 3),
                Ask("title", "anime/1"),
            ),
        )

        assertEquals(setOf("title.200.json"), stored.keys)
        assertEquals("short: not recorded, the list cannot be read in 3 pages", said[0])
        assertEquals("private: not recorded, the list could not be counted (403)", said[1])
        assertEquals(3, asked.size)
    }

    @Test
    fun `a list longer than its pages can hold is given up after one page more`() {
        api.answers(aListOf(20_000))

        recorder().record(listOf(Ask("long", "users/${RecordedUser.Public.standIn}/animelist", pages = 3)))

        assertEquals(4, asked.size)
        assertEquals(listOf("long: not recorded, the list cannot be read in 3 pages"), said)
        assertEquals(emptyMap<String, String>(), stored)
    }

    @Test
    fun `the refusal of the list of a user of the build's is an answer, and any other refusal ends the run`() {
        api.answers { request ->
            when (request.url.encodedPath) {
                "/v2/anime/5" -> answer(429, body = "Too many requests", type = "text/plain")
                "/v2/anime/6" -> answer()
                else -> answer(403, body = """{"error":"forbidden"}""")
            }
        }
        val private = Ask("private", "users/${RecordedUser.Private.standIn}/animelist")
        val after = Ask("after", "anime/6")

        recorder().record(listOf(private, after))
        val stops =
            listOf(Ask("limited", "anime/5"), Ask("title", "anime/7"), Ask("me", "users/@me"), Ask("other", "users/somebody/animelist"))
                .map { refused -> assertThrows(RecorderStop::class.java) { recorder().record(listOf(refused, after)) }.message!!.lines() }

        assertEquals(setOf("private.403.json", "after.200.json"), stored.keys)
        assertEquals(
            listOf("limited: MAL refuses requests (429), stopped", "title: MAL refuses requests (403), stopped") +
                listOf("me: MAL refuses requests (403), stopped", "other: MAL refuses requests (403), stopped"),
            stops.map { it.first() },
        )
        assertEquals(1, asked.count { it == "/v2/anime/6" })
    }

    @Test
    fun `a run that stops replaces no stored answer and leaves what it was answered beside them`() {
        val earlier = mapOf("title.200.json" to "an earlier title", "list_1.200.json" to "page 1", "list_2.200.json" to "page 2")
        into.mkdirs()
        earlier.forEach { (name, text) -> File(into, name).writeText(text) }
        api.answers { request ->
            val secondPage = request.url.queryParameter("offset") == "16" && request.url.queryParameter("limit") == "16"
            if (secondPage) answer(429, body = """{"error":"too_many_requests"}""") else aListOf(47)(request)
        }
        val list = Ask("list", "users/${RecordedUser.Public.standIn}/animelist", pages = 3)

        val stop = assertThrows(RecorderStop::class.java) { recorder().record(listOf(list, Ask("title", "anime/1"))) }

        assertEquals(earlier, stored)
        assertEquals(setOf("list_1.200.json", "list_2.429.json"), staging.texts().keys)
        assertEquals(
            listOf(
                "list_2: MAL refuses requests (429), stopped",
                "No stored answer was replaced; what this run was answered is in $staging",
            ),
            stop.message!!.lines(),
        )
        assertFalse("/v2/anime/1" in events)
    }

    @Test
    fun `a request without an answer ends the run`() {
        api.answers { dropped() }

        val stop = assertThrows(RecorderStop::class.java) { recorder().record(listOf(Ask("first", "anime/1"), Ask("after", "anime/6"))) }

        assertTrue(stop.message!!.startsWith("first: no answer ("))
        assertFalse("/v2/anime/6" in events)
        assertEquals(emptyMap<String, String>(), stored)
    }

    @Test
    fun `an answer recorded again takes the place of the earlier one`() {
        into.mkdirs()
        File(into, "title.404.json").writeText("{}")
        File(into, "title_2.200.json").writeText("{}")
        api.answers { answer() }

        recorder().record(listOf(Ask("title", "anime/1")))

        assertEquals(setOf("title.200.json", "title_2.200.json"), stored.keys)
    }

    @Test
    fun `the requests of a run are the whole list or the ones named`() {
        assertEquals(ClientIdRequests, chosen(""))
        assertEquals(listOf("title_finished", "token_invalid"), chosen("token_invalid, title_finished").map { it.name })
        assertEquals("no request is named title", assertThrows(RecorderStop::class.java) { chosen("title") }.message)
    }

    private companion object {
        const val CLIENT_ID = "the-client-id-0f3a"
        const val PUBLIC = "Some_Name-7"
        const val PRIVATE = "private_one"
        const val TOKEN = "a-token-9c1e"
        const val REFRESH = "a-refresh-51bd"
    }
}
