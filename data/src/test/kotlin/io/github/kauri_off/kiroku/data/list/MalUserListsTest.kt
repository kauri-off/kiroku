package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.user.UserListPart
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.anime.edited
import io.github.kauri_off.kiroku.data.anime.recordedJson
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalBackOff
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.network.TestClock
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import io.github.kauri_off.kiroku.data.recorder.swappedCase
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

// Other users' lists over MAL's recorded answers behind a local server, which answers the public pages by their offset.
class MalUserListsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    private val sessions by lazy { SessionFixture(folder.newFolder()) }
    private val source by lazy { MalUserLists(network.http) }
    private val asked = CopyOnWriteArrayList<RecordedRequest>()

    @After
    fun close() = runBlocking { sessions.close() }

    private fun body(name: String) = RecordedAnswers.named(name).body

    private fun serve(page: (RecordedRequest, offset: Int) -> MockResponse? = { _, _ -> null }) =
        network.answers { request ->
            asked += request
            val offset = request.url.queryParameter("offset")?.toInt() ?: 0
            page(request, offset) ?: answer(200, body("list_public_${offset / 10 + 1}"))
        }

    private fun recordedPages(): List<List<ListEntry>> = (1..3).map { recordedJson("list_public_$it").toListPage(Repairs()).entries }

    private class Read(
        val parts: List<UserListPart>,
        val end: Outcome<Unit>,
    )

    private fun read(
        name: String,
        each: (UserListPart) -> Unit = {},
    ): Read {
        val parts = mutableListOf<UserListPart>()
        val end =
            runBlocking {
                source.read(name) {
                    parts += it
                    each(it)
                }
            }
        return Read(parts, end)
    }

    @Test
    fun `a part is a page, handed over before the next page is asked, with no total and nothing of the user`() {
        serve()
        val askedWhenHanded = mutableListOf<Int>()
        val read = read("public-user") { askedWhenHanded += asked.size }

        assertEquals(Outcome.Success(Unit), read.end)
        assertEquals(recordedPages(), read.parts.map { it.entries })
        assertEquals(listOf(1, 2, 3), askedWhenHanded)
        assertEquals(listOf(null), read.parts.map { it.total }.distinct())
        assertEquals(listOf(null), read.parts.map { it.user }.distinct())
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `on the recorded answers a private list and an unknown name are their outcomes, give no part, and back nothing off`() {
        val answers =
            mapOf(
                "private-user" to (403 to "list_private"),
                "kiroku-no-user-0" to (404 to "list_unknown_user"),
                "kiroku no user" to (404 to "list_impossible_name"),
            )
        serve { request, _ -> answers.getValue(request.url.pathSegments[2]).let { (code, name) -> answer(code, body(name)) } }

        val ends = answers.keys.associateWith { name -> read(name).also { assertEquals(name, emptyList<UserListPart>(), it.parts) }.end }

        assertEquals(
            listOf(DataError.PrivateList, DataError.UnknownUser, DataError.UnknownUser).map { Outcome.Failure(it) },
            ends.values.toList(),
        )
        assertEquals(3, asked.size)
        assertEquals(Duration.ZERO, runBlocking { network.http.refusedFor() })
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `a refusal half-way ends the read with it after the parts before it, no page after it is asked, and background work backs off`() {
        for (refusal in listOf(answer(403, """{"error":"forbidden"}"""), answer(429, ""))) {
            asked.clear()
            serve { _, offset -> if (offset == 10) refusal else null }

            val read = read("public-user")

            assertEquals(Outcome.Failure(DataError.Refused), read.end)
            assertEquals(recordedPages().take(1), read.parts.map { it.entries })
            assertEquals(listOf("0", "10"), asked.map { it.url.queryParameter("offset") })
            assertEquals(MalBackOff.First, runBlocking { network.http.refusedFor() })
            network.http.backOff.answered(network.http.backOff.mark())
        }
    }

    @Test
    fun `a name reaches MAL as one segment of the address and without a token, while the session is signed in`() {
        serve { request, _ -> if (request.url.pathSegments[2] == "@me") null else answer(404, body("list_unknown_user")) }
        val session = sessions.open()
        assertEquals(Outcome.Success(Unit), runBlocking { session.signIn(TokenGrant("access-1", "refresh-1")) })
        assertTrue(runBlocking { MalLists(network.http).list(ListUser.SignedIn(session)) } is Outcome.Success)
        assertEquals(listOf("Bearer access-1"), asked.map { it.headers[AUTHORIZATION] }.distinct())
        asked.clear()

        val names = listOf("a/b", "../public-user", "a?offset=10", "a b", "a#b", "Кирокү", "名前", "%40me", "a%2Fb", "@me-not", ";@me")
        for (name in names) assertEquals(name, Outcome.Failure(DataError.UnknownUser), read(name).end)

        assertEquals(names.map { listOf("v2", "users", it, "animelist") }, asked.map { it.url.pathSegments })
        assertEquals(listOf(null), asked.map { it.headers[AUTHORIZATION] }.distinct())
    }

    @Test
    fun `MAL's word for the signed-in user, and a name that is no segment of an address, are no user's and are not asked`() {
        serve()
        val session = sessions.open()
        assertEquals(Outcome.Success(Unit), runBlocking { session.signIn(TokenGrant("access-1", "refresh-1")) })

        for (name in listOf("@me", "@ME", "@Me", "", ".", "..")) {
            val read = read(name)
            assertEquals(name, Outcome.Failure(DataError.UnknownUser), read.end)
            assertEquals(name, emptyList<UserListPart>(), read.parts)
        }
        assertEquals(emptyList<RecordedRequest>(), asked)
    }

    @Test
    fun `repairs are reported once the last part is handed over, and not by a read that failed`() {
        val repaired = recordedJson("list_public_2").edited(listOf("data", "4", "list_status", "score"), JsonPrimitive("eight"))
        serve { _, offset -> if (offset == 10) answer(200, repaired.toString()) else null }
        val reportedWhenHanded = mutableListOf<Int>()

        assertEquals(Outcome.Success(Unit), read("public-user") { reportedWhenHanded += network.unexpected.size }.end)
        assertEquals(listOf(0, 0, 0), reportedWhenHanded)
        assertEquals(listOf(setOf("list_status.score")), network.unexpected.map { (it as RepairedAnswer).fields })

        network.unexpected.clear()
        serve { _, offset ->
            when (offset) {
                10 -> answer(200, repaired.toString())
                20 -> answer(500, "")
                else -> null
            }
        }
        assertEquals(Outcome.Failure(DataError.ServerError), read("public-user").end)
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `a read cancelled while a page is asked stops at once and asks for nothing more`() {
        serve { _, offset ->
            if (offset ==
                10
            ) {
                answer(200, body("list_public_2")).newBuilder().headersDelay(3, TimeUnit.SECONDS).build()
            } else {
                null
            }
        }
        var parts = 0
        var ended: Outcome<Unit>? = null
        runBlocking {
            val reading = launch(Dispatchers.Default) { ended = source.read("public-user") { parts++ } }
            withTimeout(5.seconds) { while (asked.size < 2) delay(10) }
            assertTrue(measureTime { reading.cancelAndJoin() } < 1.seconds)
        }
        assertEquals(1, parts)
        assertNull(ended)
        assertEquals(2, asked.size)
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `two names are one user when they differ only in the case of Latin letters, as MAL answered`() {
        val name = "Some_Name-7"
        assertTrue(source.sameUser(name, swappedCase(name)))
        assertTrue(source.sameUser(name, name.uppercase()))
        assertTrue(source.sameUser(name, name))
        val apart =
            listOf(
                "Some_Name-8",
                "Some_Name-7 ",
                "Some_Name7",
                "",
                // The Kelvin sign, which some rules fold to k.
                "Kaori" to "kaori",
                "Ёж" to "ёж",
                "Straße" to "STRASSE",
                "İd" to "id",
            )
        for (other in apart) {
            val (a, b) = if (other is Pair<*, *>) other.first as String to other.second as String else name to other as String
            assertFalse("$a $b", source.sameUser(a, b))
            assertFalse("$b $a", source.sameUser(b, a))
        }
    }

    @Test
    fun `whether two names are one user is answered on the main thread without building the client`() {
        val http =
            MalHttp(
                hosts = MalHosts.Real,
                clientId = NetworkFixture.CLIENT_ID,
                appVersion = "1.2.3",
                device =
                    object : Device {
                        override fun isOnline() = error("Asked of the device")

                        override fun observeOnline() = error("Asked of the device")

                        override fun isMainThread() = true
                    },
                clock = TestClock(),
                backOffFile = { error("Asked for the back-off") },
                unexpected = { throw it },
            )
        val users = MalUserLists(http)
        assertTrue(users.sameUser("Some_Name", "some_name"))
        assertFalse(users.sameUser("Some_Name", "Other"))
    }
}
