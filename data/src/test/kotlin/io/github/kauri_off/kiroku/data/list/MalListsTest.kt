package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.data.anime.edited
import io.github.kauri_off.kiroku.data.anime.recordedJson
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.UnusableAnswer
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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

    private fun recordedEntries(): List<ListEntry> = (1..3).flatMap { recordedJson("list_public_$it").toListPage().entries }

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
        val broken = recordedJson("list_public_2").edited(listOf("data", "4", "list_status", "status"), JsonPrimitive("rewatching"))
        serve { offset -> if (offset == 10) answer(200, broken.toString()) else null }

        assertEquals(Outcome.Failure(DataError.ServerError), download())
        assertTrue(network.unexpected.single() is UnusableAnswer)
        assertTrue(asked.none { it.url.queryParameter("offset") == "20" })
    }

    @Test
    fun `a next link back to a page already read ends the download as a failure`() {
        val back =
            recordedJson(
                "list_public_2",
            ).edited(listOf("paging", "next"), recordedJson("list_public_2").getValue("paging").jsonObject.getValue("previous"))
        serve { offset -> if (offset == 10) answer(200, back.toString()) else null }

        assertEquals(Outcome.Failure(DataError.ServerError), download())
        assertEquals(listOf("0", "10"), asked.map { it.url.queryParameter("offset") })
        assertTrue(network.unexpected.single() is UnusableAnswer)
    }

    @Test
    fun `a list that always has a next page ends the download as a failure after a bounded number of pages`() {
        val first = recordedJson("list_public_1")
        val next =
            first
                .getValue("paging")
                .jsonObject
                .getValue("next")
                .let { (it as JsonPrimitive).content }
        serve { offset ->
            answer(
                200,
                first.edited(listOf("paging", "next"), JsonPrimitive(next.replace("offset=10", "offset=${offset + 10}"))).toString(),
            )
        }

        assertEquals(Outcome.Failure(DataError.ServerError), download())
        assertEquals(100, asked.size)
        assertTrue(network.unexpected.single() is UnusableAnswer)
    }

    @Test
    fun `the next page is asked of MAL's own host whatever host the link names`() {
        val elsewhere =
            recordedJson(
                "list_public_1",
            ).edited(listOf("paging", "next"), JsonPrimitive("https://elsewhere.example/v2/other?offset=10"))
        serve { offset -> if (offset == 0) answer(200, elsewhere.toString()) else null }

        assertEquals(recordedEntries(), (download() as Outcome.Success).value)
        assertTrue(asked.all { it.url.encodedPath == "/v2/users/public-user/animelist" && it.url.queryParameter("nsfw") == "true" })
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
    fun `another user's private list and an unknown user are told apart`() {
        network.always(answer(403, body("list_private")))
        assertEquals(Outcome.Failure(DataError.PrivateList), download())
        network.always(answer(404, body("list_unknown_user")))
        assertEquals(Outcome.Failure(DataError.UnknownUser), download(ListUser.Named("kiroku-no-user-0")))
        assertTrue(network.unexpected.isEmpty())
    }
}
