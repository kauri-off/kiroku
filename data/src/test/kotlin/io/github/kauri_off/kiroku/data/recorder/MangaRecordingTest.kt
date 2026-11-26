package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class MangaRecordingTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    private val fixture by lazy { SessionFixture(folder.newFolder()) }
    private val session by lazy { runBlocking { fixture.open().also { it.signIn(TokenGrant(ACCESS, "test-refresh")) } } }
    private val journal by lazy { File(folder.root, "recording") }
    private val root by lazy { File(folder.root, "no_backup/private-manga-mutations") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writes = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile private var values: JsonObject? = null

    @Volatile private var otherValues: JsonObject? = null

    @Volatile private var override: (RecordedRequest) -> MockResponse? = { null }

    @Before
    fun serve() {
        network.answers { request ->
            if (request.method != "GET") writes += request
            override(request) ?: answer(request)
        }
    }

    @After
    fun close() =
        runBlocking {
            scope.cancel()
            fixture.close()
        }

    @Test
    fun `the checked absent title is added edited read back and removed in one private recording`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            assertEquals(emptyList<RecordedRequest>(), writes)
            assertFalse(journal.exists())
            recorder.record(title.copy()).join()
            assertEquals(emptyList<RecordedRequest>(), writes)

            override = { request ->
                if (request.method == "DELETE") {
                    values = null
                    json("null")
                } else {
                    null
                }
            }
            recorder.record(title).join()

            val complete = recorder.state.value as Recording.Recorded
            assertEquals(MangaRecordingAnswers, complete.answers)
            assertEquals(ListAfter.Removed, complete.list)
            assertEquals(root, complete.folder.parentFile)
            assertFalse(journal.exists())
            assertEquals(null, values)
            assertEquals(listOf("PATCH", "PATCH", "DELETE"), writes.map { it.method })
            assertTrue(writes.all { it.url.encodedPath == "/v2/manga/$ID/my_list_status" && it.headers[AUTHORIZATION] == "Bearer $ACCESS" })
            assertEquals(
                MangaWrites.map { (_, form) -> form },
                writes.filter { it.method == "PATCH" }.map(::form),
            )
            validateMangaMutationCapture(complete.folder)
            assertEquals("null", complete.folder.resolve("$MANGA_REMOVED.200.json").readText())
            val evidence =
                complete.folder
                    .listFiles()
                    .orEmpty()
                    .joinToString { it.readText() }
            for (secret in listOf(ACCESS, NetworkFixture.CLIENT_ID, "Fixture title", "private-fixture")) {
                assertFalse(evidence.contains(secret))
            }
            recorder.forgetMangaCaptures()
            assertFalse(root.exists())
            assertEquals(Recording.Idle, recorder.state.value)
        }

    @Test
    fun `an existing row including a row added between check and confirm is never mutated`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            values = planned
            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.OnTheList, stopped.halt)
            assertEquals(ListAfter.Untouched, stopped.list)
            assertFalse(journal.exists())
            assertEquals(emptyList<RecordedRequest>(), writes)
            assertEquals(planned, values)

            recorder.check(ID, RecorderMedia.Manga).join()
            assertEquals(Recording.NotStarted(Halt.OnTheList), recorder.state.value)
            assertEquals(emptyList<RecordedRequest>(), writes)
        }

    @Test
    fun `a recorded private GET proves membership even on a later page`() =
        runBlocking {
            val pages = OwnerRecordings.mangaPages()
            OwnerRecordings.read("Personal manga recorder preflight") {
                val last = Json.parseToJsonElement(pages.last().body).jsonObject
                val target =
                    last
                        .getValue("data")
                        .jsonArray
                        .last()
                        .jsonObject
                val id = (target.getValue("node").jsonObject.getValue("id") as JsonPrimitive).long
                override = { request ->
                    val offset = request.url.queryParameter("offset")
                    if (offset != null) {
                        val index = offset.toInt() / 2
                        json(pages[index].body)
                    } else if (request.url.encodedPath == "/v2/manga/$id") {
                        json("""{"id":$id,"title":"Fixture title"}""")
                    } else {
                        null
                    }
                }

                val recorder = recorder()
                recorder.check(id, RecorderMedia.Manga).join()
                assertEquals(Recording.NotStarted(Halt.OnTheList), recorder.state.value)
                assertEquals(emptyList<RecordedRequest>(), writes)
                assertFalse(root.exists())
            }
        }

    @Test
    fun `ambiguous pagination prevents preflight and prevents a confirmed run from sending changes`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            override = { request ->
                if (request.url.encodedPath.endsWith("/mangalist")) {
                    json("""{"data":[],"paging":{},"paging":{"next":"https://invalid.test/?offset=1"}}""")
                } else {
                    null
                }
            }

            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.Unexpected(200), stopped.halt)
            assertEquals(ListAfter.Untouched, stopped.list)
            assertEquals(emptyList<RecordedRequest>(), writes)
            assertFalse(journal.exists())
        }

    @Test
    fun `a replacement sign in cannot confirm a checked title even for the same MAL account`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            session.signIn(TokenGrant(ACCESS, "other-test-refresh"))

            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.AnotherAccount, stopped.halt)
            assertEquals(ListAfter.Untouched, stopped.list)
            assertEquals(emptyList<RecordedRequest>(), writes)
            assertFalse(journal.exists())
        }

    @Test
    fun `an unconfirmed edit is cleaned up and never published as complete`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            override = { request ->
                if (request.method == "PATCH" && form(request)["status"] == "reading") json("""{"score":7}""") else null
            }

            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.Unconfirmed, stopped.halt)
            assertEquals(ListAfter.Removed, stopped.list)
            assertEquals(null, values)
            assertFalse(journal.exists())
            assertFalse(checkNotNull(stopped.folder).resolve(MANGA_CAPTURE_COMPLETE).exists())
        }

    @Test
    fun `a storage failure after a PATCH cleans the temporary row and keeps older captures separate`() =
        runBlocking {
            var failAfterPatch = false
            val recorder =
                recorder { file, contents ->
                    if (failAfterPatch && writes.any { it.method == "PATCH" }) throw IOException("fixture storage failure")
                    file.writeText(contents)
                }
            recorder.record(checked(recorder)).join()
            val earlier = (recorder.state.value as Recording.Recorded).folder
            writes.clear()
            failAfterPatch = true
            val title = checked(recorder)

            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.Storage, stopped.halt)
            assertEquals(ListAfter.Removed, stopped.list)
            assertEquals(listOf("PATCH", "DELETE"), writes.map { it.method })
            assertFalse(journal.exists())
            validateMangaMutationCapture(earlier)
            assertEquals(null, values)
        }

    @Test
    fun `interruption keeps an owner checked journal for recovery and never touches a replacement row`() =
        runBlocking {
            val afterPatch = CompletableDeferred<Unit>()
            val stay = CompletableDeferred<Unit>()
            val recorder =
                recorder(pause = {
                    if (writes.any { it.method == "PATCH" }) {
                        afterPatch.complete(Unit)
                        stay.await()
                    }
                })
            val title = checked(recorder)
            val job = recorder.record(title)
            withTimeout(10_000) { afterPatch.await() }
            job.cancelAndJoin()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.Interrupted, stopped.halt)
            assertEquals(ListAfter.Left, stopped.list)
            assertEquals(RecorderMedia.Manga, Journal(journal).read()?.title?.media)
            assertTrue(journal.exists())

            otherValues = planned
            session.signIn(TokenGrant(OTHER, "other-test-refresh"))
            val reopened = recorder()
            assertEquals(Recording.Leftover(title, ListAfter.Left, Halt.AnotherAccount), reopened.state.value)
            assertTrue(journal.exists())
            assertEquals(listOf("PATCH"), writes.map { it.method })
            assertEquals(planned, otherValues)

            session.signIn(TokenGrant(ACCESS, "owner-returned"))
            reopened.remove().join()
            assertEquals(Recording.Leftover(title, ListAfter.Removed, null), reopened.state.value)
            assertEquals(listOf("PATCH", "DELETE"), writes.map { it.method })
            assertFalse(journal.exists())
            assertEquals(null, values)
            assertEquals(planned, otherValues)
        }

    @Test
    fun `replacement during recording stops all further mutations and leaves recovery to the original account`() =
        runBlocking {
            val afterPatch = CompletableDeferred<Unit>()
            val continueRun = CompletableDeferred<Unit>()
            val recorder =
                recorder(pause = {
                    if (writes.any { it.method == "PATCH" }) {
                        afterPatch.complete(Unit)
                        continueRun.await()
                    }
                })
            val title = checked(recorder)
            val job = recorder.record(title)
            withTimeout(10_000) { afterPatch.await() }
            otherValues = planned
            session.signIn(TokenGrant(OTHER, "other-test-refresh"))
            continueRun.complete(Unit)
            job.join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.AnotherAccount, stopped.halt)
            assertEquals(ListAfter.Left, stopped.list)
            recorder.remove().join()
            assertEquals(Recording.Leftover(title, ListAfter.Left, Halt.AnotherAccount), recorder.state.value)
            assertEquals(listOf("PATCH"), writes.map { it.method })
            assertEquals(planned, otherValues)
            assertTrue(journal.exists())
        }

    @Test
    fun `a successful DELETE reply still requires authoritative absence before clearing the journal`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            override = { request -> if (request.method == "DELETE") MockResponse(code = 204) else null }

            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.StillOnTheList, stopped.halt)
            assertEquals(ListAfter.Left, stopped.list)
            assertTrue(journal.exists())
            assertEquals(listOf("PATCH", "PATCH", "DELETE"), writes.map { it.method })

            override = { null }
            recorder.remove().join()
            assertEquals(Recording.Leftover(title, ListAfter.Removed, null), recorder.state.value)
            assertFalse(journal.exists())
        }

    private suspend fun recorder(
        pause: suspend () -> Unit = {},
        write: (File, String) -> Unit = { file, contents -> file.writeText(contents) },
    ): SignedInRecorder =
        SignedInRecorder(
            lazyOf(session),
            network.http,
            { journal },
            { error("Manga answers must stay private") },
            { error("Write recordings must have their own folder") },
            scope,
            pause,
            writePrivateFile = write,
            privateMutations = { root },
        ).also { it.started.join() }

    private suspend fun checked(recorder: SignedInRecorder): RecordedTitle {
        recorder.check(ID, RecorderMedia.Manga).join()
        return (recorder.state.value as Recording.Checked).title
    }

    private val planned = JsonObject(mapOf("status" to JsonPrimitive("plan_to_read")))

    private fun answer(request: RecordedRequest): MockResponse {
        val original = request.headers[AUTHORIZATION] == "Bearer $ACCESS"
        return when (request.url.encodedPath) {
            "/v2/manga/$ID" -> json("""{"id":$ID,"title":"Fixture title"}""")
            "/v2/users/@me" -> json("""{"id":${if (original) 45 else 46}}""")
            "/v2/users/@me/mangalist" -> {
                check(request.url.queryParameter("nsfw") == "true")
                val entry =
                    (if (original) values else otherValues)?.let { row ->
                        JsonObject(
                            mapOf(
                                "node" to JsonObject(mapOf("id" to JsonPrimitive(ID), "title" to JsonPrimitive("private-fixture"))),
                                "list_status" to row,
                            ),
                        )
                    }
                json(JsonObject(mapOf("data" to JsonArray(listOfNotNull(entry)), "paging" to JsonObject(emptyMap()))).toString())
            }
            "/v2/manga/$ID/my_list_status" ->
                when (request.method) {
                    "PATCH" -> {
                        check(Journal(journal).read()?.title?.media == RecorderMedia.Manga)
                        val reply =
                            JsonObject(
                                form(request).mapValues { (_, value) ->
                                    value.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
                                },
                            )
                        if (original) values = reply else otherValues = reply
                        json(JsonObject(reply + ("irrelevant" to JsonPrimitive("private-fixture $ACCESS"))).toString())
                    }
                    "DELETE" -> {
                        if (original) values = null else otherValues = null
                        MockResponse(code = 204)
                    }
                    else -> error("Unexpected mutation")
                }
            else -> error("Unexpected request")
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun form(request: RecordedRequest): Map<String, String> =
        request.body
            ?.utf8()
            .orEmpty()
            .split('&')
            .associate { part -> part.substringBefore('=') to part.substringAfter('=') }

    private companion object {
        const val ID = 478L
        const val ACCESS = "manga-test-access"
        const val OTHER = "manga-replacement-test-access"
    }
}
