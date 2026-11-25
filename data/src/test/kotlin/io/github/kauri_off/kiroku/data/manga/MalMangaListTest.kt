package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import io.github.kauri_off.kiroku.data.session.granted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MalMangaListTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    private val sessions by lazy { SessionFixture(folder.newFolder()) }
    private val session by lazy { sessions.open() }
    private val list by lazy { MalMangaList(network.http, session) }
    private val id = MangaId(1)

    @After
    fun close() = runBlocking { sessions.close() }

    private suspend fun signIn(access: String): SignInId {
        assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant(access, "refresh-$access")))
        return checkNotNull(session.currentSession()?.owner)
    }

    // Public anime recordings exercise only the common MAL paging envelope, with item contents left opaque.
    private fun envelope(page: Int) = answer(200, RecordedAnswers.named("list_public_$page").body)

    @Test
    fun `the entire list is collected using only the next offset from each recorded envelope`() =
        runBlocking {
            val owner = signIn("first")
            for (page in 1..3) network.api.enqueue(envelope(page))
            val result = list.fetch(owner) as Outcome.Success

            assertEquals(30, result.value.size)
            for (offset in listOf("0", "10", "20")) {
                val request = network.api.received()
                assertEquals("GET", request.method)
                assertEquals("/v2/users/@me/mangalist", request.url.encodedPath)
                assertEquals(offset, request.url.queryParameter("offset"))
                assertEquals("1000", request.url.queryParameter("limit"))
                assertEquals("manga_title", request.url.queryParameter("sort"))
                assertEquals("true", request.url.queryParameter("nsfw"))
                assertNull(request.url.queryParameter("status"))
                val fields = checkNotNull(request.url.queryParameter("fields"))
                assertTrue(fields.contains("list_status{status,score,num_chapters_read,num_volumes_read,updated_at}"))
                assertEquals("Bearer first", request.headers[AUTHORIZATION])
            }
            assertEquals(3, network.api.requestCount)
        }

    @Test
    fun `failure in the middle discards the partial snapshot`() =
        runBlocking {
            val owner = signIn("first")
            network.api.enqueue(envelope(1))
            network.api.enqueue(answer(400, ""))

            assertEquals(Outcome.Failure(DataError.InvalidRequest), list.fetch(owner))
            assertEquals(2, network.api.requestCount)
        }

    @Test
    fun `a repeated cursor in the middle fails without sending another page`() =
        runBlocking {
            val owner = signIn("first")
            repeat(2) { network.api.enqueue(envelope(1)) }

            assertEquals(Outcome.Failure(DataError.ServerError), list.fetch(owner))
            assertEquals(2, network.api.requestCount)
        }

    @Test
    fun `a second page that arrives after sign out and sign in is discarded`() =
        runBlocking {
            val owner = signIn("first")
            val release = CountDownLatch(1)
            network.answers { request ->
                if (request.url.queryParameter("offset") == "0") {
                    envelope(1)
                } else {
                    check(release.await(10, TimeUnit.SECONDS))
                    envelope(2)
                }
            }

            val pending = async { list.fetch(owner) }
            try {
                withContext(Dispatchers.IO) {
                    val first = network.api.received()
                    val second = network.api.received()
                    assertEquals("0", first.url.queryParameter("offset"))
                    assertEquals("10", second.url.queryParameter("offset"))
                }
                session.signOut()
                assertNotEquals(owner, signIn("second"))
            } finally {
                release.countDown()
            }
            assertEquals(Outcome.Failure(DataError.ListEnded), pending.await())
            assertEquals(2, network.api.requestCount)
        }

    @Test
    fun `stale operations send nothing even for the same manga id`() =
        runBlocking {
            val old = signIn("first")
            session.signOut()
            val current = signIn("second")
            network.always(answer(404, ""))

            assertEquals(Outcome.Failure(DataError.ListEnded), list.fetch(old))
            assertEquals(Outcome.Failure(DataError.ListEnded), list.patch(id, MangaListPatch(score = 3), old))
            assertEquals(Outcome.Failure(DataError.ListEnded), list.delete(id, old))
            assertEquals(0, network.api.requestCount)

            assertEquals(Outcome.Failure(DataError.NotFound), list.delete(id, current))
            val request = network.api.received()
            assertEquals("DELETE", request.method)
            assertEquals("/v2/manga/1/my_list_status", request.url.encodedPath)
            assertEquals("Bearer second", request.headers[AUTHORIZATION])
        }

    @Test
    fun `patch sends only the specified changes and preserves explicit zeroes`() =
        runBlocking {
            val owner = signIn("first")
            network.always(answer(400, ""))

            assertEquals(
                Outcome.Failure(DataError.InvalidRequest),
                list.patch(id, MangaListPatch(score = 0, volumesRead = 0), owner),
            )
            val request = network.api.received()
            assertEquals("PATCH", request.method)
            assertEquals("/v2/manga/1/my_list_status", request.url.encodedPath)
            assertEquals("Bearer first", request.headers[AUTHORIZATION])
            assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
            assertEquals("score=0&num_volumes_read=0", request.body?.utf8())
        }

    @Test
    fun `status, chapter and volume form values use manga codes`() {
        val codes = MangaListStatus.entries.map { MangaListPatch(status = it, chaptersRead = 7, volumesRead = 2).form() }
        assertEquals(listOf("reading", "completed", "on_hold", "dropped", "plan_to_read"), codes.map { it["status"] })
        for (form in codes) {
            assertEquals(setOf("status", "num_chapters_read", "num_volumes_read"), form.keys)
            assertEquals("7", form["num_chapters_read"])
            assertEquals("2", form["num_volumes_read"])
        }
        assertThrows(IllegalArgumentException::class.java) { MangaListPatch(score = -1) }
        assertThrows(IllegalArgumentException::class.java) { MangaListPatch(score = 11) }
        assertThrows(IllegalArgumentException::class.java) { MangaListPatch(chaptersRead = -1) }
        assertThrows(IllegalArgumentException::class.java) { MangaListPatch(volumesRead = -1) }
    }

    @Test
    fun `empty patches and invalid ids send nothing`() =
        runBlocking {
            val owner = signIn("first")
            assertEquals(Outcome.Failure(DataError.InvalidRequest), list.patch(id, MangaListPatch(), owner))
            assertEquals(Outcome.Failure(DataError.NotFound), list.patch(MangaId(0), MangaListPatch(score = 1), owner))
            assertEquals(Outcome.Failure(DataError.NotFound), list.delete(MangaId(-1), owner))
            assertEquals(0, network.api.requestCount)
        }

    @Test
    fun `a rejected bearer refreshes in the same sign in and retries with the new token`() =
        runBlocking {
            val owner = signIn("first")
            sessions.answer = { granted("renewed", "renewed-refresh") }
            network.api.enqueue(answer(401, ""))
            network.api.enqueue(answer(400, ""))

            assertEquals(Outcome.Failure(DataError.InvalidRequest), list.patch(id, MangaListPatch(chaptersRead = 2), owner))
            val first = network.api.received()
            val repeated = network.api.received()
            assertEquals("Bearer first", first.headers[AUTHORIZATION])
            assertEquals("Bearer renewed", repeated.headers[AUTHORIZATION])
            assertEquals(first.method, repeated.method)
            assertEquals(first.url, repeated.url)
            assertEquals(first.body, repeated.body)
            assertEquals(owner, session.currentSession()?.owner)
            assertEquals(1, sessions.grants.size)
        }

    @Test
    fun `a late write acknowledgement is discarded when another sign in owns the same manga`() =
        runBlocking {
            val owner = signIn("first")
            val release = CountDownLatch(1)
            network.answers {
                check(release.await(10, TimeUnit.SECONDS))
                answer(204, "")
            }

            val pending = async { list.delete(id, owner) }
            try {
                val sent = withContext(Dispatchers.IO) { network.api.received() }
                assertEquals("Bearer first", sent.headers[AUTHORIZATION])
                session.signOut()
                assertNotEquals(owner, signIn("second"))
            } finally {
                release.countDown()
            }
            assertEquals(Outcome.Failure(DataError.ListEnded), pending.await())
            assertEquals(1, network.api.requestCount)
        }

    @Test
    fun `a write exposes the HTTP status and an absent body without interpreting either`() =
        runBlocking {
            val owner = signIn("first")
            network.api.enqueue(answer(204, ""))

            val result = list.delete(id, owner) as Outcome.Success
            assertEquals(204, result.value.status)
            assertNull(result.value.body)
        }

    @Test
    fun `server failures do not replay writes and redirected success does not acknowledge them`() =
        runBlocking {
            val owner = signIn("first")
            network.api.enqueue(answer(500, ""))
            network.api.enqueue(answer(500, ""))
            assertEquals(Outcome.Failure(DataError.ServerError), list.patch(id, MangaListPatch(score = 5), owner))
            assertEquals(Outcome.Failure(DataError.ServerError), list.delete(id, owner))
            assertEquals(2, network.api.requestCount)

            val redirect =
                MockResponse
                    .Builder()
                    .code(303)
                    .addHeader("Location", "/read")
                    .build()
            network.api.enqueue(redirect)
            network.api.enqueue(answer(204, ""))
            assertEquals(Outcome.Failure(DataError.ServerError), list.delete(id, owner))
        }
}
