package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.data.anime.edited
import io.github.kauri_off.kiroku.data.anime.recordedJson
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.CLIENT_ID_HEADER
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.recorder.OwnerRecordings
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import io.github.kauri_off.kiroku.data.recorder.mangaCaptureFiles
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import io.github.kauri_off.kiroku.data.session.granted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import okhttp3.Dispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MalMangaListTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    private val sessions by lazy { SessionFixture(folder.newFolder()) }
    private val session by lazy { sessions.open() }
    private val list by lazy { MalMangaList(network.http, session) }
    private val source by lazy { MalMangaListSource(network.http, session) }
    private val id = MangaId(1)

    @After
    fun close() = runBlocking { sessions.close() }

    private suspend fun signIn(access: String): SignInId {
        assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant(access, "refresh-$access")))
        return checkNotNull(session.currentSession()?.owner)
    }

    // Public anime recordings exercise common MAL paging and membership fields.
    private fun envelope(page: Int) = answer(200, RecordedAnswers.named("list_public_$page").body)

    private fun redirect(path: String) =
        MockResponse
            .Builder()
            .code(303)
            .addHeader("Location", path)
            .build()

    @Test
    fun `private replay errors omit exception and assertion values`() {
        for (failure in listOf(IllegalStateException("private exception"), AssertionError("private assertion"))) {
            val redacted =
                assertThrows(AssertionError::class.java) {
                    OwnerRecordings.read("personal manga list") { throw failure }
                }
            assertEquals("personal manga list could not be read", redacted.message)
            assertNull(redacted.cause)
            assertTrue(redacted.suppressed.isEmpty())
        }
    }

    @Test
    fun `explicit manga replay rejects missing and ambiguous pages`() {
        val names = (1..5).map { "manga_list_$it" }
        val files = names.map { folder.newFile("$it.200.json") }
        val duplicate = folder.newFile("manga_list_5.400.json")
        for (selection in listOf(files.dropLast(1), files + duplicate)) {
            val failure = assertThrows(AssertionError::class.java) { mangaCaptureFiles(selection, required = true) }
            assertEquals("Personal manga list capture must contain five unique HTTP 200 pages", failure.message)
            assertNull(failure.cause)
        }
        assertEquals(files, mangaCaptureFiles(files, required = true))
    }

    @Test
    fun `recorded manga pages map in order with an authenticated GET at each offset`() =
        runBlocking {
            val pages = OwnerRecordings.mangaPages()
            OwnerRecordings.read("personal manga list") {
                network.clientId = ""
                val owner = signIn("replay")
                val expected =
                    pages.flatMap {
                        check(it.status == 200)
                        network.api.enqueue(answer(it.status, it.body))
                        MalJson
                            .parseToJsonElement(it.body)
                            .jsonObject
                            .getValue("data")
                            .jsonArray
                    }
                val result = source.fetch(owner) { true }
                check(result is Outcome.Success)
                check(result.value.size == expected.size && expected.size == 10)
                for ((entry, wire) in result.value.zip(expected)) {
                    val node = wire.jsonObject.getValue("node").jsonObject
                    val values = wire.jsonObject.getValue("list_status").jsonObject
                    val alternative = node.getValue("alternative_titles").jsonObject
                    check(entry.id.value == node.getValue("id").jsonPrimitive.long)
                    check(entry.titles.romaji == node.getValue("title").jsonPrimitive.content)
                    check(entry.titles.english == alternative["en"]?.jsonPrimitive?.content?.takeUnless(String::isBlank))
                    check(entry.titles.japanese == alternative["ja"]?.jsonPrimitive?.content?.takeUnless(String::isBlank))
                    check(entry.titles.synonyms == alternative.getValue("synonyms").jsonArray.map { it.jsonPrimitive.content })
                    check(
                        entry.posterUrl ==
                            node
                                .getValue("main_picture")
                                .jsonObject
                                .getValue("medium")
                                .jsonPrimitive.content,
                    )
                    check(entry.status.code == values.getValue("status").jsonPrimitive.content)
                    check(entry.chaptersRead == values.getValue("num_chapters_read").jsonPrimitive.int)
                    check(entry.volumesRead == values.getValue("num_volumes_read").jsonPrimitive.int)
                    check(
                        entry.score ==
                            values
                                .getValue("score")
                                .jsonPrimitive.int
                                .takeIf { it > 0 },
                    )
                    check(entry.updatedAt == OffsetDateTime.parse(values.getValue("updated_at").jsonPrimitive.content).toInstant())
                }
                check(network.api.requestCount == pages.size)
                for (offset in listOf(0, 2, 4, 6, 8)) {
                    val request = network.api.received()
                    check(request.method == "GET" && request.url.encodedPath == "/v2/users/@me/mangalist")
                    check(request.url.queryParameter("offset") == offset.toString())
                    check(request.url.queryParameter("nsfw") == "true" && request.url.queryParameter("status") == null)
                    check(request.headers[AUTHORIZATION] == "Bearer replay" && request.headers[CLIENT_ID_HEADER] == null)
                }
                check(network.unexpected.isEmpty())
            }
        }

    @Test
    fun `a queued first GET checks visibility and owner when OkHttp can send it`() =
        runBlocking {
            val dispatcher = Dispatcher().apply { maxRequests = 1 }
            network.calls = dispatcher
            network.repeatReadsAfter = emptyList()
            for (replaceOwner in listOf(false, true)) {
                val visible = AtomicBoolean(true)
                val owner = signIn("first")
                val release = CountDownLatch(1)
                network.answers {
                    check(release.await(5, TimeUnit.SECONDS))
                    answer(503, "")
                }
                val occupying = async(Dispatchers.Default) { network.http.call { network.things.value.read() } }
                val pending =
                    try {
                        assertEquals(
                            "/v2/thing",
                            network.api
                                .received()
                                .url.encodedPath,
                        )
                        val queued = async(Dispatchers.Default) { source.fetch(owner, visible::get) }
                        withTimeout(5_000) {
                            while (dispatcher.queuedCallsCount() == 0) delay(1)
                        }
                        if (replaceOwner) signIn("replacement") else visible.set(false)
                        queued
                    } finally {
                        release.countDown()
                    }
                occupying.await()
                val result = pending.await()
                if (replaceOwner) assertEquals(Outcome.Failure(DataError.ListEnded), result) else assertNull(result)
            }
            assertEquals(2, network.api.requestCount)
            assertTrue(network.unexpected.isEmpty())
        }

    @Test
    fun `an admitted first GET can renew and read later pages after the route is covered`() =
        runBlocking {
            val owner = signIn("first")
            sessions.answer = { granted("renewed", "renewed-refresh") }
            val visible = AtomicBoolean(true)
            network.answers { request ->
                when {
                    request.headers[AUTHORIZATION] == "Bearer first" -> {
                        visible.set(false)
                        answer(401, "")
                    }
                    request.url.queryParameter("offset") == "0" -> envelope(1)
                    request.url.queryParameter("offset") == "10" -> envelope(2)
                    else -> envelope(3)
                }
            }
            val result = source.fetch(owner, visible::get)
            assertTrue(result is Outcome.Success)
            assertEquals(4, network.api.requestCount)
            assertEquals("Bearer first", network.api.received().headers[AUTHORIZATION])
            repeat(3) { assertEquals("Bearer renewed", network.api.received().headers[AUTHORIZATION]) }
        }

    @Test
    fun `the entire list is collected using only the next offset from each recorded envelope`() =
        runBlocking {
            val owner = signIn("first")
            for (page in 1..3) network.api.enqueue(envelope(page))
            val result = list.fetch(owner) as Outcome.Success

            val download = result.value
            val ids = download.rows.map { it.id }.toSet()
            assertEquals(owner, download.owner)
            assertEquals(30, download.rows.size)
            assertEquals(30, ids.size)
            assertTrue(download.rows.any { it.statusCode == "watching" })
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
    fun `manga list membership rejects absent or invalid ids and statuses`() =
        runBlocking {
            val owner = signIn("first")
            val terminal = recordedJson("list_public_3")
            val id = listOf("data", "0", "node", "id")
            val status = listOf("data", "0", "list_status", "status")
            val broken =
                listOf(
                    terminal.edited(listOf("data", "0", "node"), JsonObject(emptyMap())),
                    terminal.edited(id),
                    terminal.edited(id, JsonPrimitive(0)),
                    terminal.edited(id, JsonPrimitive(1.5)),
                    terminal.edited(id, JsonPrimitive("123")),
                    terminal.edited(listOf("data", "0", "list_status"), JsonNull),
                    terminal.edited(status),
                    terminal.edited(status, JsonPrimitive(" ")),
                    terminal.edited(status, JsonPrimitive(1)),
                )
            for (page in broken) {
                network.api.enqueue(answer(200, page.toString()))
                assertEquals(Outcome.Failure(DataError.ServerError), list.fetch(owner))
            }
        }

    @Test
    fun `an id repeated on a page or on a later page rejects the entire download`() =
        runBlocking {
            val owner = signIn("first")
            val terminal = recordedJson("list_public_3")
            val rows = terminal.getValue("data").jsonArray
            val row = rows.first().jsonObject
            val id = listOf("data", "1", "node", "id")
            val repeated = terminal.edited(id, row.getValue("node").jsonObject.getValue("id"))
            network.api.enqueue(answer(200, repeated.toString()))
            assertEquals(Outcome.Failure(DataError.ServerError), list.fetch(owner))

            val first = recordedJson("list_public_1")
            val second = recordedJson("list_public_2")
            val firstRows = first.getValue("data").jsonArray
            val firstRow = firstRows.first().jsonObject
            val acrossPages = second.edited(id, firstRow.getValue("node").jsonObject.getValue("id"))
            network.api.enqueue(envelope(1))
            network.api.enqueue(answer(200, acrossPages.toString()))
            assertEquals(Outcome.Failure(DataError.ServerError), list.fetch(owner))
            assertEquals(3, network.api.requestCount)
        }

    @Test
    fun `a direct terminal page can be empty but partial content cannot end the download`() =
        runBlocking {
            val owner = signIn("first")
            val terminal = recordedJson("list_public_3").edited(listOf("data"), JsonArray(emptyList()))
            network.api.enqueue(answer(200, terminal.toString()))
            val result = list.fetch(owner) as Outcome.Success
            assertTrue(result.value.rows.isEmpty())

            network.api.enqueue(answer(206, terminal.toString()))
            assertEquals(Outcome.Failure(DataError.ServerError), list.fetch(owner))
        }

    @Test
    fun `a gap or an ambiguous offset rejects the page before any follow up`() =
        runBlocking {
            val owner = signIn("first")
            val first = recordedJson("list_public_1")
            val paging = first.getValue("paging").jsonObject
            val link = paging.getValue("next").jsonPrimitive.content
            for (next in listOf(link.replace("offset=10", "offset=11"), "$link&offset=200")) {
                val page = first.edited(listOf("paging", "next"), JsonPrimitive(next))
                network.api.enqueue(answer(200, page.toString()))
                assertEquals(Outcome.Failure(DataError.ServerError), list.fetch(owner))
            }
            assertEquals(2, network.api.requestCount)
        }

    @Test
    fun `a redirect to a valid terminal envelope cannot supply the snapshot`() =
        runBlocking {
            val owner = signIn("first")
            network.api.enqueue(envelope(1))
            network.api.enqueue(redirect("/v2/users/other/mangalist?status=reading"))
            network.api.enqueue(envelope(3))

            assertEquals(Outcome.Failure(DataError.ServerError), list.fetch(owner))
            assertEquals(3, network.api.requestCount)
        }

    @Test
    fun `a redirected unauthorized answer cannot renew the bearer`() =
        runBlocking {
            val owner = signIn("first")
            network.api.enqueue(redirect("/v2/other"))
            network.api.enqueue(answer(401, ""))

            assertEquals(Outcome.Failure(DataError.ServerError), list.fetch(owner))
            assertEquals(2, network.api.requestCount)
            assertTrue(sessions.grants.isEmpty())
            assertEquals(owner, session.currentSession()?.owner)
        }

    @Test
    fun `a same route HTTP retry can still supply the snapshot`() =
        runBlocking {
            val owner = signIn("first")
            network.api.enqueue(
                MockResponse
                    .Builder()
                    .code(503)
                    .setHeader("Retry-After", "0")
                    .build(),
            )
            network.api.enqueue(envelope(3))

            val result = list.fetch(owner) as Outcome.Success
            assertEquals(10, result.value.rows.size)
            assertEquals(2, network.api.requestCount)
            val first = network.api.received()
            val retry = network.api.received()
            assertEquals(first.url, retry.url)
            assertEquals("Bearer first", retry.headers[AUTHORIZATION])
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
            assertEquals(Outcome.Failure(DataError.ListEnded), list.patch(id, MangaListEdit(score = 3), old))
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
                list.patch(id, MangaListEdit(score = 0, volumesRead = 0), owner),
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
        val codes = MangaListStatus.entries.map { MangaListEdit(status = it, chaptersRead = 7, volumesRead = 2).form() }
        assertEquals(listOf("reading", "completed", "on_hold", "dropped", "plan_to_read"), codes.map { it["status"] })
        for (form in codes) {
            assertEquals(setOf("status", "num_chapters_read", "num_volumes_read"), form.keys)
            assertEquals("7", form["num_chapters_read"])
            assertEquals("2", form["num_volumes_read"])
        }
        assertThrows(IllegalArgumentException::class.java) { MangaListEdit(score = -1) }
        assertThrows(IllegalArgumentException::class.java) { MangaListEdit(score = 11) }
        assertThrows(IllegalArgumentException::class.java) { MangaListEdit(chaptersRead = -1) }
        assertThrows(IllegalArgumentException::class.java) { MangaListEdit(volumesRead = -1) }
    }

    @Test
    fun `empty patches and invalid ids send nothing`() =
        runBlocking {
            val owner = signIn("first")
            assertEquals(Outcome.Failure(DataError.InvalidRequest), list.patch(id, MangaListEdit(), owner))
            assertEquals(Outcome.Failure(DataError.NotFound), list.patch(MangaId(0), MangaListEdit(score = 1), owner))
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

            assertEquals(Outcome.Failure(DataError.InvalidRequest), list.patch(id, MangaListEdit(chaptersRead = 2), owner))
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
    fun `a patch waiting for OkHttp cannot send after replacement sign in`() =
        runBlocking {
            val dispatcher = Dispatcher().apply { maxRequests = 1 }
            network.calls = dispatcher
            network.repeatReadsAfter = emptyList()
            val owner = signIn("first")
            val release = CountDownLatch(1)
            network.answers {
                check(release.await(5, TimeUnit.SECONDS))
                answer(400, "")
            }
            val occupying = async(Dispatchers.Default) { network.http.call { network.things.value.read() } }
            val queued =
                try {
                    assertEquals(
                        "/v2/thing",
                        network.api
                            .received()
                            .url.encodedPath,
                    )
                    val write = async(Dispatchers.Default) { list.patch(id, MangaListEdit(score = 3), owner) }
                    withTimeout(5_000) {
                        while (dispatcher.queuedCallsCount() == 0) delay(1)
                    }
                    assertNotEquals(owner, signIn("second"))
                    write
                } finally {
                    release.countDown()
                }
            occupying.await()
            assertEquals(Outcome.Failure(DataError.ListEnded), queued.await())
            assertEquals(1, network.api.requestCount)
        }

    @Test
    fun `a queued patch is blocked while sign out is still in local cleanup`() =
        runBlocking {
            val dispatcher = Dispatcher().apply { maxRequests = 1 }
            network.calls = dispatcher
            network.repeatReadsAfter = emptyList()
            val owner = signIn("first")
            val wireFree = CountDownLatch(1)
            val cleanup = CompletableDeferred<Unit>()
            val finishSignOut = CompletableDeferred<Unit>()
            network.answers {
                check(wireFree.await(5, TimeUnit.SECONDS))
                answer(400, "")
            }
            val occupying = async(Dispatchers.Default) { network.http.call { network.things.value.read() } }
            try {
                assertEquals(
                    "/v2/thing",
                    network.api
                        .received()
                        .url.encodedPath,
                )
                val queued = async(Dispatchers.Default) { list.patch(id, MangaListEdit(score = 3), owner) }
                withTimeout(5_000) {
                    while (dispatcher.queuedCallsCount() == 0) delay(1)
                }
                val leaving =
                    async {
                        session.signOutIfCurrent(owner) {
                            cleanup.complete(Unit)
                            finishSignOut.await()
                        }
                    }
                withTimeout(5_000) { cleanup.await() }
                assertEquals(owner, session.currentSession()?.owner)
                wireFree.countDown()
                occupying.await()
                assertEquals(Outcome.Failure(DataError.ListEnded), queued.await())
                assertEquals(1, network.api.requestCount)
                finishSignOut.complete(Unit)
                assertTrue(leaving.await())
            } finally {
                wireFree.countDown()
                finishSignOut.complete(Unit)
            }
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
            assertEquals("application/json; charset=utf-8", result.value.mediaType)
        }

    @Test
    fun `server failures do not replay writes and redirected success does not acknowledge them`() =
        runBlocking {
            val owner = signIn("first")
            network.api.enqueue(answer(500, ""))
            network.api.enqueue(answer(500, ""))
            assertEquals(Outcome.Failure(DataError.ServerError), list.patch(id, MangaListEdit(score = 5), owner))
            assertEquals(Outcome.Failure(DataError.ServerError), list.delete(id, owner))
            assertEquals(2, network.api.requestCount)

            network.api.enqueue(redirect("/read"))
            network.api.enqueue(answer(204, ""))
            assertEquals(Outcome.Failure(DataError.ServerError), list.delete(id, owner))
        }
}
