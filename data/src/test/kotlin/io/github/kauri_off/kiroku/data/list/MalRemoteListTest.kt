package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.list.ListEngine
import io.github.kauri_off.kiroku.core.list.SentValues
import io.github.kauri_off.kiroku.core.list.sent
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListField
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.anime.edited
import io.github.kauri_off.kiroku.data.anime.recordedJson
import io.github.kauri_off.kiroku.data.anime.recordedTitle
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.AnotherTitleAnswer
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.recorder.PlantedMal
import io.github.kauri_off.kiroku.data.recorder.PlantedMal.Companion.ACCESS
import io.github.kauri_off.kiroku.data.recorder.PlantedMal.Companion.CHANGED_AT
import io.github.kauri_off.kiroku.data.recorder.PlantedMal.Companion.REFRESH
import io.github.kauri_off.kiroku.data.recorder.PlantedMal.Companion.TITLE
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import io.github.kauri_off.kiroku.data.session.granted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLDecoder
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Year
import java.time.YearMonth
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The client of the signed-in user's list against the recorder's stand-in, which keeps a list as requests change it.
class MalRemoteListTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    private val mal = PlantedMal()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val sessions by lazy { SessionFixture(folder.newFolder()) }
    private val anime = recordedTitle("title_finished").anime.copy(id = AnimeId(TITLE))
    private val listed = AnimeId(101)

    @Before
    fun serve() {
        network.api.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return mal.dispatch(request)
                }
            }
    }

    @After
    fun close() = runBlocking { sessions.close() }

    private val client by lazy { client(signedIn = true) }

    private fun client(signedIn: Boolean): MalRemoteList {
        val session = sessions.open()
        if (signedIn) runBlocking { session.signIn(TokenGrant(ACCESS, REFRESH)) }
        return MalRemoteList(network.http, session)
    }

    private fun RecordedRequest.form(): Map<String, String> =
        body!!.utf8().split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private val values =
        ListValues(
            status = ListStatus.Completed,
            watchedEpisodes = 12,
            score = 8,
            startDate = PartialDate.Day(LocalDate.of(2020, 1, 2)),
            finishDate = PartialDate.Month(YearMonth.of(2020, 3)),
        )

    @Test
    fun `a change is one PATCH of the title's list status as the user, and answers with the entry as MAL has it then`() {
        val entry = runBlocking { client.put(anime, values.sent) }

        assertEquals(Outcome.Success(ListEntry(anime, values, OffsetDateTime.parse(CHANGED_AT).toInstant())), entry)
        val request = requests.single()
        assertEquals("PATCH", request.method)
        assertEquals("/v2/anime/$TITLE/my_list_status", request.url.encodedPath)
        assertNull(request.url.query)
        assertEquals("Bearer $ACCESS", request.headers[AUTHORIZATION])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/x-www-form-urlencoded"))
        val sent =
            mapOf(
                "status" to "completed",
                "score" to "8",
                "num_watched_episodes" to "12",
                "start_date" to "2020-01-02",
                "finish_date" to "2020-03",
            )
        assertEquals(sent, request.form())
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `each status is sent as MAL's code, no score and no date as MAL's empty values, a date with its precision`() {
        val codes =
            mapOf(
                ListStatus.Watching to "watching",
                ListStatus.Planned to "plan_to_watch",
                ListStatus.Completed to "completed",
                ListStatus.OnHold to "on_hold",
                ListStatus.Dropped to "dropped",
            )
        assertEquals(ListStatus.entries.toSet(), codes.keys)
        for ((status, code) in codes) {
            assertEquals(code, values.copy(status = status).sent.form()["status"])
        }
        val empty = ListValues(ListStatus.Planned, 0, null, null, null).sent.form()
        assertEquals(
            mapOf(
                "status" to "plan_to_watch",
                "score" to "0",
                "num_watched_episodes" to "0",
                "start_date" to "",
                "finish_date" to "",
            ),
            empty,
        )
        assertEquals("2020", values.copy(startDate = PartialDate.OnlyYear(Year.of(2020))).sent.form()["start_date"])

        for (status in ListStatus.entries) {
            requests.clear()
            val sent = values.copy(status = status, score = null, finishDate = null)
            val answer = runBlocking { client.put(anime, sent.sent) } as Outcome.Success
            assertEquals(status.name, sent, answer.value!!.values)
            assertEquals(codes.getValue(status), requests.single().form()["status"])
        }
    }

    // A value a write does not carry is left out, and MAL keeps its own (the recordings' rule).
    @Test
    fun `a write leaves out every value it does not carry`() {
        val form =
            SentValues(
                values.copy(watchedEpisodes = 3, finishDate = null),
                setOf(ListField.WatchedEpisodes, ListField.FinishDate),
            ).form()
        assertEquals(mapOf("num_watched_episodes" to "3", "finish_date" to ""), form)
    }

    @Test
    fun `a write to an entry whose status the app does not know carries no status field`() {
        val read = (runBlocking { client.fetchEntry(listed) } as Outcome.Success).value!!
        val unknown = read.values.copy(status = EntryStatus.Unrecognized("rewatching"), watchedEpisodes = read.values.watchedEpisodes + 1)

        assertTrue(runBlocking { client.put(read.anime, unknown.sent) } is Outcome.Success)

        val write = requests.last()
        assertEquals("PATCH", write.method)
        assertEquals(setOf("score", "num_watched_episodes", "start_date", "finish_date"), write.form().keys)
        assertEquals("${unknown.watchedEpisodes}", write.form()["num_watched_episodes"])
    }

    @Test
    fun `a write carries the values it is given alone, and nothing else MAL gave the entry`() {
        val read = (runBlocking { client.fetchEntry(listed) } as Outcome.Success).value!!
        assertTrue(mal.bodies.last().contains("comments"))

        val stepped = read.values.copy(watchedEpisodes = read.values.watchedEpisodes + 1)
        runBlocking { client.put(read.anime, SentValues(stepped, setOf(ListField.WatchedEpisodes))) }

        val write = requests.last()
        assertEquals("PATCH", write.method)
        assertEquals(mapOf("num_watched_episodes" to "25"), write.form())
        assertEquals("25", mal.onTheList().getValue(listed.value)["num_episodes_watched"])
    }

    // Nothing is filled in, no date's precision lost.
    @Test
    fun `each value of a recorded list, when a write carries it, is in the form MAL gave it`() {
        for (name in (1..3).map { "list_public_$it" }) assertWrittenAsGiven(name, RecordedAnswers.named(name).body)
    }

    // A fixture title edited over the real account, in any build: MAL holds real titles under such ids one day.
    @Test
    fun `no write for a made-up title is sent to MAL, nor a read of its entry, and each answer is MAL's for one it does not know`() {
        val film = FixtureAnime.film
        assertTrue(film.id.madeUp)
        assertEquals(Outcome.Failure(DataError.NotFound), runBlocking { client.put(film, values.sent) })
        assertEquals(Outcome.Success(null), runBlocking { client.put(film, null) })
        assertEquals(Outcome.Failure(DataError.NotFound), runBlocking { client.fetchEntry(film.id) })
        assertEquals(emptyList<String>(), requests.map { it.url.encodedPath })

        val highest = anime.copy(id = AnimeId(AnimeId.MADE_UP - 1))
        runBlocking { client.put(highest, values.sent) }
        assertEquals(listOf("/v2/anime/${highest.id.value}/my_list_status"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `a removal is a DELETE as the user that answers with no entry, and so is the removal of a title not on the list`() {
        val title = anime.copy(id = listed)
        assertEquals(Outcome.Success(null), runBlocking { client.put(title, null) })
        val removal = requests.single()
        assertEquals("DELETE", removal.method)
        assertEquals("/v2/anime/${listed.value}/my_list_status", removal.url.encodedPath)
        assertEquals("Bearer $ACCESS", removal.headers[AUTHORIZATION])
        assertTrue(listed.value !in mal.onTheList())

        // The stand-in answers 404 for a title not on the list, as MAL's reference says; MAL itself answered as above.
        assertEquals(Outcome.Success(null), runBlocking { client.put(title, null) })
        assertEquals(2, requests.size)
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `an entry is read from the title's own answer with its list values, as the user, and a title not on the list has none`() {
        val entry = (runBlocking { client.fetchEntry(listed) } as Outcome.Success).value!!
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("/v2/anime/${listed.value}", request.url.encodedPath)
        assertEquals(MalFields.OWN_ENTRY, request.url.queryParameter("fields"))
        assertEquals("Bearer $ACCESS", request.headers[AUTHORIZATION])
        assertEquals(listed, entry.anime.id)
        val expected =
            ListValues(
                ListStatus.Completed,
                24,
                9,
                PartialDate.Day(LocalDate.of(2015, 4, 5)),
                PartialDate.Day(LocalDate.of(2015, 6, 21)),
            )
        assertEquals(expected, entry.values)
        assertEquals(OffsetDateTime.parse(PlantedMal.UPDATED_AT).toInstant(), entry.updatedAt)

        assertEquals(Outcome.Success(null), runBlocking { client.fetchEntry(AnimeId(TITLE)) })
        assertEquals(Outcome.Failure(DataError.NotFound), runBlocking { client.fetchEntry(AnimeId(999)) })
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `a title's answer about another title is no entry of the one asked, and is reported`() {
        runBlocking { client.fetchEntry(AnimeId(102)) }
        val another = mal.bodies.last()
        mal.instead = { if (it.url.encodedPath == "/v2/anime/${listed.value}") answer(200, another) else null }

        assertEquals(Outcome.Failure(DataError.ServerError), runBlocking { client.fetchEntry(listed) })
        assertTrue(network.unexpected.single() is AnotherTitleAnswer)
    }

    @Test
    fun `a write MAL answers with a value it gave unreadably is read without it, and reported`() {
        val status =
            recordedJson("list_public_1")
                .getValue("data")
                .jsonArray[0]
                .jsonObject
                .getValue("list_status")
                .jsonObject
        val unreadable = status.edited(listOf("score"), JsonPrimitive("eight"))
        mal.instead = { if (it.method == "PATCH") answer(200, unreadable.toString()) else null }

        val entry = (runBlocking { client.put(anime, values.sent) } as Outcome.Success).value!!

        assertNull(entry.values.score)
        assertEquals(anime, entry.anime)
        assertEquals(setOf("list_status.score"), (network.unexpected.single() as RepairedAnswer).fields)
    }

    @Test
    fun `a refused value, a limit, a server failure and no session are the failures the engine expects, and a write is sent once`() {
        val failures =
            listOf(
                Triple(400, "{}", DataError.InvalidRequest),
                Triple(403, NetworkFixture.MalsRefusal, DataError.Refused),
                Triple(403, "<html><body>Blocked</body></html>", DataError.ServerError),
                Triple(429, "{}", DataError.Refused),
                Triple(500, "{}", DataError.ServerError),
            )
        for ((status, body, error) in failures) {
            for (write in listOf(values, null)) {
                requests.clear()
                mal.instead = { answer(status, body) }
                assertEquals("$status $write", Outcome.Failure(error), runBlocking { client.put(anime, write?.sent) })
                assertEquals("$status $write", 1, requests.size)
            }
        }
        mal.instead = { null }
        requests.clear()
        val signedOut = client(signedIn = false)
        assertEquals(Outcome.Failure(DataError.SessionExpired), runBlocking { signedOut.put(anime, values.sent) })
        assertEquals(Outcome.Failure(DataError.SessionExpired), runBlocking { signedOut.put(anime, null) })
        assertEquals(Outcome.Failure(DataError.SessionExpired), runBlocking { signedOut.fetchEntry(listed) })
        assertTrue(requests.isEmpty())
    }

    // The owner's pages go through the same function: a page that cannot be decoded fails by its name alone.
    @Test
    fun `a list page that cannot be decoded fails the round trip by its name, with no value of it in the failure`() {
        val marker = "planted-value-3e9a"
        val pages =
            listOf(
                recordedJson("list_public_1").edited(listOf("data", "0", "node", "title"), JsonObject(mapOf("x" to JsonPrimitive(marker)))),
                recordedJson("list_public_1").edited(listOf("data", "0", "node"), JsonArray(listOf(JsonPrimitive(marker)))),
            )
        for (page in pages) {
            val failure = assertThrows(AssertionError::class.java) { assertWrittenAsGiven("list_public_1", page.toString()) }
            val printed = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }.toString()
            assertEquals("list_public_1 could not be read", failure.message)
            assertTrue(marker !in printed)
        }
    }

    @Test
    fun `a removal is confirmed only by MAL's answer to one, not by another success or a page a redirect led to`() {
        val title = anime.copy(id = listed)
        val others =
            listOf(
                answer(200, ""),
                answer(200, "<html>portal</html>", "text/html"),
                answer(200, "{}"),
                answer(200, "[1]"),
            )
        for (other in others) {
            mal.instead = { if (it.method == "DELETE") other else null }
            assertEquals(Outcome.Failure(DataError.ServerError), runBlocking { client.put(title, null) })
        }
        val elsewhere = network.api.url("/elsewhere")
        mal.instead = {
            when {
                it.method == "DELETE" ->
                    MockResponse
                        .Builder()
                        .code(303)
                        .setHeader("Location", elsewhere)
                        .build()
                it.url.encodedPath == "/elsewhere" -> answer(200, "<html>portal</html>", "text/html")
                else -> null
            }
        }
        assertEquals(Outcome.Failure(DataError.ServerError), runBlocking { client.put(title, null) })
        assertTrue(listed.value in mal.onTheList())
    }

    @Test
    fun `a write whose token MAL rejects is sent once more after one refresh, with the new token`() {
        sessions.answer = { granted(ACCESS, "refresh-2") }
        val session = sessions.open()
        val stale = MalRemoteList(network.http, session)

        runBlocking { session.signIn(TokenGrant("stale-access-1", REFRESH)) }
        val entry = runBlocking { stale.put(anime, values.sent) }
        assertEquals(Outcome.Success(ListEntry(anime, values, OffsetDateTime.parse(CHANGED_AT).toInstant())), entry)
        assertEquals(1, sessions.grants.size)
        assertEquals(
            listOf("Bearer stale-access-1", "Bearer $ACCESS"),
            requests.filter { it.method == "PATCH" }.map { it.headers[AUTHORIZATION] },
        )

        runBlocking { session.signIn(TokenGrant("stale-access-2", REFRESH)) }
        assertEquals(Outcome.Success(null), runBlocking { stale.put(anime.copy(id = listed), null) })
        assertEquals(2, sessions.grants.size)
        assertEquals(
            listOf("Bearer stale-access-2", "Bearer $ACCESS"),
            requests.filter { it.method == "DELETE" }.map { it.headers[AUTHORIZATION] },
        )
        assertTrue(listed.value !in mal.onTheList())
    }

    // The engine over this client: an unreadable score is read as none, and a "+1" leaves it out, so MAL keeps its own.
    @Test
    fun `a +1 after a score MAL gave unreadably leaves the score out, and the row then shows MAL's`() {
        val page = recordedJson("list_public_1").edited(listOf("data", "0", "list_status", "score"), JsonPrimitive("eight"))
        val read = page.toListPage(Repairs()).entries[0].let { it.copy(anime = it.anime.copy(id = listed)) }
        assertNull(read.values.score)
        val owner = SignInId("owner")
        val store = InMemoryLocalList(listOf(read), owner)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val unexpected = CopyOnWriteArrayList<Throwable>()
        try {
            val engine = ListEngine(client, store, flowOf(Session.SignedIn(owner)), Clock.systemUTC(), scope) { unexpected += it }
            runBlocking {
                engine.write(ListWrite.MarkNextWatched(read.anime))
                withTimeout(10.seconds) { while (store.read(listed)?.entry?.unconfirmed != false) delay(10.milliseconds) }
            }
        } finally {
            scope.cancel()
        }

        assertEquals(mapOf("num_watched_episodes" to "${read.values.watchedEpisodes + 1}"), requests.single().form())
        assertEquals("9", mal.onTheList().getValue(listed.value)["score"])
        assertEquals(
            9,
            runBlocking { store.read(listed) }
                ?.entry
                ?.shown
                ?.values
                ?.score,
        )
        assertEquals(emptyList<Throwable>(), unexpected)
    }

    @Test
    fun `the whole list is the signed-in user's, read as the user`() {
        val entries = (runBlocking { client.fetch() } as Outcome.Success).value
        assertEquals(mal.onTheList().keys, entries.map { it.anime.id.value }.toSet())
        assertTrue(requests.all { it.url.encodedPath == "/v2/users/@me/animelist" && it.headers[AUTHORIZATION] == "Bearer $ACCESS" })
    }
}
