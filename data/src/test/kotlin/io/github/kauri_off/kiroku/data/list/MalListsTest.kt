package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.anime.edited
import io.github.kauri_off.kiroku.data.anime.recordedJson
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.MalBackOff
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.network.UnusableAnswer
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

// The list download over the recorded public pages behind a local server, which answers by the page's offset.
class MalListsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    private val sessions by lazy { SessionFixture(folder.newFolder()) }
    private val lists by lazy { MalLists(network.http, sessions.open()) }
    private val asked = CopyOnWriteArrayList<RecordedRequest>()
    private val user = ListUser.Named("public-user")

    @After
    fun close() = runBlocking { sessions.close() }

    private fun body(name: String) = RecordedAnswers.named(name).body

    // The three recorded pages, at the offsets their links name; [page] answers in place of one of them.
    private fun serve(page: (offset: Int) -> MockResponse? = { null }) =
        network.answers { request ->
            asked += request
            val offset = request.url.queryParameter("offset")?.toInt() ?: 0
            page(offset) ?: answer(200, body("list_public_${offset / 10 + 1}"))
        }

    private fun recordedEntries(): List<ListEntry> = (1..3).flatMap { recordedJson("list_public_$it").toListPage(Repairs()).entries }

    private fun download(of: ListUser = user) = runBlocking { lists.list(of) }

    @Test
    fun `three pages give every entry once, in their order, each page asked with adult titles and in the titles' order`() {
        serve()
        val outcome = download()

        val entries = (outcome as Outcome.Success).value
        assertEquals(recordedEntries(), entries)
        assertEquals(30, entries.map { it.anime.id }.toSet().size)
        assertEquals(listOf("0", "10", "20"), asked.map { it.url.queryParameter("offset") })
        for (request in asked) {
            assertEquals("/v2/users/public-user/animelist", request.url.encodedPath)
            assertEquals("true", request.url.queryParameter("nsfw"))
            assertEquals("anime_title", request.url.queryParameter("sort"))
            assertEquals(MalFields.LIST_ENTRY, request.url.queryParameter("fields"))
            assertEquals("1000", request.url.queryParameter("limit"))
            assertNull(request.headers[AUTHORIZATION])
        }
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `a middle page that fails fails the download, and the pages after it are not asked`() {
        serve { offset -> if (offset == 10) answer(500, "") else null }
        assertEquals(Outcome.Failure(DataError.ServerError), download())
        assertTrue(asked.none { it.url.queryParameter("offset") == "20" })

        asked.clear()
        serve { offset -> if (offset == 10) answer(429, "") else null }
        assertEquals(Outcome.Failure(DataError.Refused), download())
        assertTrue(asked.none { it.url.queryParameter("offset") == "20" })
    }

    @Test
    fun `a middle page with an entry that cannot be mapped fails the download, and is reported`() {
        val broken = recordedJson("list_public_2").edited(listOf("data", "4", "list_status", "status"), JsonNull)
        serve { offset -> if (offset == 10) answer(200, broken.toString()) else null }

        assertEquals(Outcome.Failure(DataError.ServerError), download())
        assertTrue(network.unexpected.single() is UnusableAnswer)
        assertTrue(asked.none { it.url.queryParameter("offset") == "20" })
    }

    // Each page's repairs are reported once, with the same report as a title page's, only when the whole list is read.
    @Test
    fun `a repaired title and a repaired list value are each reported once per download, and a clean list reports nothing`() {
        val node = recordedJson("list_public_2").edited(listOf("data", "4", "node", "mean"), JsonPrimitive("broken"))
        val values = recordedJson("list_public_3").edited(listOf("data", "0", "list_status", "score"), JsonPrimitive("eight"))
        serve { offset ->
            when (offset) {
                10 -> answer(200, node.toString())
                20 -> answer(200, values.toString())
                else -> null
            }
        }

        repeat(2) { download() as Outcome.Success }

        val reported = network.unexpected.map { (it as RepairedAnswer).fields }
        assertEquals(List(2) { listOf(setOf("node.mean"), setOf("list_status.score")) }.flatten(), reported)

        network.unexpected.clear()
        serve()
        download() as Outcome.Success
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `an entry whose list status the app does not know is downloaded with MAL's code and reported, and the list is whole`() {
        val unknown = recordedJson("list_public_2").edited(listOf("data", "4", "list_status", "status"), JsonPrimitive("rewatching"))
        serve { offset -> if (offset == 10) answer(200, unknown.toString()) else null }

        val entries = (download() as Outcome.Success).value

        val expected = recordedEntries().toMutableList()
        expected[14] = expected[14].copy(values = expected[14].values.copy(status = EntryStatus.Unrecognized("rewatching")))
        assertEquals(expected, entries)
        assertEquals(listOf(setOf("list_status.status")), network.unexpected.map { (it as RepairedAnswer).fields })
    }

    @Test
    fun `a download that fails reports nothing of the repaired pages it read`() {
        val repaired = recordedJson("list_public_1").edited(listOf("data", "0", "list_status", "score"), JsonPrimitive("eight"))
        serve { offset ->
            when (offset) {
                0 -> answer(200, repaired.toString())
                10 -> answer(500, "")
                else -> null
            }
        }

        assertEquals(Outcome.Failure(DataError.ServerError), download())
        assertTrue(network.unexpected.isEmpty())
    }

    // A recorded page whose next link is [link].
    private fun pageWithNext(
        name: String,
        link: String,
    ) = recordedJson(name).edited(listOf("paging", "next"), JsonPrimitive(link)).toString()

    private val link = "https://api.myanimelist.net/v2/users/public-user/animelist"

    @Test
    fun `a next link that does not start where the page ended fails the download, and no page after it is asked`() {
        val wrong =
            listOf(
                "$link?offset=0",
                "$link?offset=10",
                "$link?offset=19",
                "$link?offset=25",
                "$link?offset=1000000",
                link,
                "$link?offset=x",
            )
        for (next in wrong) {
            asked.clear()
            network.unexpected.clear()
            serve { offset -> if (offset == 10) answer(200, pageWithNext("list_public_2", next)) else null }

            assertEquals(next, Outcome.Failure(DataError.ServerError), download())
            assertEquals(next, listOf("0", "10"), asked.map { it.url.queryParameter("offset") })
            assertTrue(next, network.unexpected.single() is UnusableAnswer)
        }
    }

    @Test
    fun `a list longer than the bound fails at the page that passes it, whatever size the pages are`() {
        serve { offset -> answer(200, pageWithNext("list_public_1", "$link?offset=${offset + 10}")) }
        val bounded = MalLists(network.http, sessions.open(), maxEntries = 25)

        assertEquals(Outcome.Failure(DataError.ServerError), runBlocking { bounded.list(user) })
        assertEquals(listOf("0", "10", "20"), asked.map { it.url.queryParameter("offset") })
        assertTrue(network.unexpected.single() is UnusableAnswer)

        serve()
        val exactly = MalLists(network.http, sessions.open(), maxEntries = 30)
        assertEquals(recordedEntries(), (runBlocking { exactly.list(user) } as Outcome.Success).value)
    }

    @Test
    fun `only the offset of a next link reaches the next request, on MAL's own host`() {
        val next = "https://elsewhere.example/v2/other?offset=10&status=watching&limit=10&nsfw=false&sort=list_score&fields=id"
        serve { offset -> if (offset == 0) answer(200, pageWithNext("list_public_1", next)) else null }

        assertEquals(recordedEntries(), (download() as Outcome.Success).value)
        val second = asked[1].url
        assertEquals("/v2/users/public-user/animelist", second.encodedPath)
        val expected =
            mapOf(
                "fields" to MalFields.LIST_ENTRY,
                "nsfw" to "true",
                "sort" to "anime_title",
                "limit" to "1000",
                "offset" to "10",
            )
        assertEquals(expected, second.queryParameterNames.associateWith { second.queryParameterValues(it).single() })
    }

    @Test
    fun `a page without its entries fails the download, first or in the middle with a next link`() {
        for ((at, name) in listOf(0 to "list_public_1", 10 to "list_public_2")) {
            for (data in listOf(null, JsonNull)) {
                asked.clear()
                network.unexpected.clear()
                val empty = recordedJson(name).edited(listOf("data"), data).toString()
                serve { offset -> if (offset == at) answer(200, empty) else null }

                assertEquals("$name $data", Outcome.Failure(DataError.ServerError), download())
                assertEquals(listOf("0", "10").take(at / 10 + 1), asked.map { it.url.queryParameter("offset") })
                assertTrue(network.unexpected.single() is UnusableAnswer)
            }
        }
    }

    @Test
    fun `the signed-in user's list is asked as that user, with the token`() {
        serve()
        val session = sessions.open()
        assertEquals(Outcome.Success(Unit), runBlocking { session.signIn(TokenGrant("access-1", "refresh-1")) })
        val signedIn = MalLists(network.http, session)

        assertEquals(recordedEntries(), (runBlocking { signedIn.list(ListUser.SignedIn) } as Outcome.Success).value)
        for (request in asked) {
            assertEquals("/v2/users/@me/animelist", request.url.encodedPath)
            assertEquals("Bearer access-1", request.headers[AUTHORIZATION])
            assertEquals("true", request.url.queryParameter("nsfw"))
        }
    }

    @Test
    fun `on the recorded answers a private list and an unknown name are told apart, and nothing backs off`() {
        serve { answer(403, body("list_private")) }
        assertEquals(Outcome.Failure(DataError.PrivateList), download(ListUser.Named("private-user")))
        serve { answer(404, body("list_unknown_user")) }
        assertEquals(Outcome.Failure(DataError.UnknownUser), download(ListUser.Named("kiroku-no-user-0")))
        serve { answer(404, body("list_impossible_name")) }
        assertEquals(Outcome.Failure(DataError.UnknownUser), download(ListUser.Named("kiroku no user")))

        assertEquals(
            listOf("private-user", "kiroku-no-user-0", "kiroku%20no%20user").map {
                "/v2/users/$it/animelist"
            },
            asked.map { it.url.encodedPath },
        )
        assertEquals(Duration.ZERO, network.http.backOff.remaining())
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `a refusal of another user's list that is not MAL's answer for a private list, or too many requests, backs off`() {
        for (refusal in listOf(answer(403, """{"error":"forbidden"}"""), answer(429, ""))) {
            serve { offset -> if (offset == 10) refusal else null }
            assertEquals(Outcome.Failure(DataError.Refused), download())
            assertEquals(MalBackOff.First, network.http.backOff.remaining())
            network.http.backOff.answered(network.http.backOff.mark())
        }
        assertEquals(listOf("0", "10", "0", "10"), asked.map { it.url.queryParameter("offset") })
    }

    @Test
    fun `MAL gives a public list asked by its owner's name in another letter case as asked by the name itself`() {
        val ownCase = recordedJson("list_public_1").toListPage(Repairs())
        val anotherCase = recordedJson("list_public_another_case").toListPage(Repairs())

        assertEquals(ownCase.entries.size, anotherCase.entries.size)
        assertEquals(ownCase.entries.map { it.anime.id to it.values }, anotherCase.entries.map { it.anime.id to it.values })
        assertEquals(ownCase.entries.map { it.updatedAt }, anotherCase.entries.map { it.updatedAt })
        assertEquals(ownCase.next, anotherCase.next)
    }
}
