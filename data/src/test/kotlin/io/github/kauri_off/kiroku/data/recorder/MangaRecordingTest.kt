package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import io.github.kauri_off.kiroku.data.session.TokenReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.Duration
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
    private var recorderJob = SupervisorJob()
    private val writes = CopyOnWriteArrayList<RecordedRequest>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile private var values: JsonObject? = null

    @Volatile private var otherValues: JsonObject? = null

    @Volatile private var override: (RecordedRequest) -> MockResponse? = { null }

    @Before
    fun serve() {
        network.answers { request ->
            requests += request
            if (request.method != "GET") writes += request
            override(request) ?: answer(request)
        }
    }

    @After
    fun close() =
        runBlocking {
            recorderJob.cancelAndJoin()
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
    fun `an unconfirmed edit leaves verify-only recovery and never publishes`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            override = { request ->
                if (request.method == "PATCH" && form(request)["status"] == "reading") json("""{"score":7}""") else null
            }

            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.Unconfirmed, stopped.halt)
            assertEquals("manga_edit_read", stopped.at)
            assertEquals(ListAfter.Unresolved, stopped.list)
            assertEquals(planned, values)
            assertTrue(journal.exists())
            recorder.recheck().join()
            assertEquals(listOf("PATCH", "PATCH"), writes.map { it.method })
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
                    if (writes.size == 1 &&
                        root.listFiles().orEmpty().any { it.resolve("$MANGA_BEFORE_EDIT.200.json").isFile }
                    ) {
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
            assertEquals(null, stopped.folder)
            assertEquals(0, stopped.answers)
            assertTrue(root.listFiles().orEmpty().isEmpty())
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
                    if (writes.size == 1 &&
                        root.listFiles().orEmpty().any { it.resolve("$MANGA_BEFORE_EDIT.200.json").isFile }
                    ) {
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
            val recovered = recorder.state.value as Recording.Stopped
            assertEquals(ListAfter.Removed, recovered.list)
            assertEquals(stopped.folder, recovered.folder)
            assertEquals(stopped.answers, recovered.answers)
            assertTrue(checkNotNull(recovered.folder).isDirectory)
            assertFalse(journal.exists())
        }

    @Test
    fun `cancelling before first dispatch never deletes a row the user later adds`() =
        runBlocking {
            val intentWritten = CompletableDeferred<Unit>()
            val stay = CompletableDeferred<Unit>()
            val recorder =
                recorder(pause = {
                    if (journal.exists() && writes.isEmpty()) {
                        intentWritten.complete(Unit)
                        stay.await()
                    }
                })
            val job = recorder.record(checked(recorder))
            withTimeout(10_000) { intentWritten.await() }
            job.cancelAndJoin()
            values = planned

            recorder()
            assertEquals(emptyList<RecordedRequest>(), writes)
            assertFalse(journal.exists())
            assertEquals(planned, values)
        }

    @Test
    fun `a definitive refusal of the first patch never deletes a subsequently added row`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            override = { request -> if (request.method == "PATCH") MockResponse(code = 429) else null }
            recorder.record(title).join()
            val stopped = recorder.state.value as Recording.Stopped
            values = planned
            network.clock.now = network.clock.now.plusSeconds(901)

            recorder()
            assertEquals(listOf("PATCH"), writes.map { it.method })
            assertEquals(ListAfter.Untouched, stopped.list)
            assertFalse(journal.exists())
            assertEquals(planned, values)
        }

    @Test
    fun `replacement before the first dispatch retires the unspent intent`() =
        runBlocking {
            val intentWritten = CompletableDeferred<Unit>()
            val continueRun = CompletableDeferred<Unit>()
            val recorder =
                recorder(pause = {
                    if (journal.exists() && writes.isEmpty()) {
                        intentWritten.complete(Unit)
                        continueRun.await()
                    }
                })
            val job = recorder.record(checked(recorder))
            withTimeout(10_000) { intentWritten.await() }
            session.signIn(TokenGrant(OTHER, "other-test-refresh"))
            continueRun.complete(Unit)
            job.join()
            assertEquals(emptyList<RecordedRequest>(), writes)
            assertFalse(journal.exists())
        }

    @Test
    fun `a legacy or ambiguous intent never authorizes deleting an existing row`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            override = { request ->
                if (request.method == "PATCH") {
                    values = planned
                    response(500, """{"error":"server_error"}""")
                } else {
                    null
                }
            }
            recorder.record(title).join()
            val reopened = recorder()
            assertEquals("Unresolved", (reopened.state.value as Recording.Leftover).list.name)
            reopened.recheck().join()
            assertEquals(listOf("PATCH"), writes.map { it.method })
            assertTrue(journal.exists())
            assertEquals(planned, values)

            values = null
            reopened.recheck().join()
            assertEquals("Absent", (reopened.state.value as Recording.Leftover).list.name)
            assertFalse(journal.exists())
        }

    @Test
    fun `invalid row provenance after a successful add keeps recovery verify only`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            override = { request ->
                if (request.url.encodedPath.endsWith("/mangalist") && writes.size == 1) {
                    values = JsonObject(planned + ("updated_at" to JsonPrimitive("invalid timestamp")))
                }
                null
            }
            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals("manga_add_read", stopped.at)
            assertEquals(ListAfter.Unresolved, stopped.list)
            recorder.recheck().join()
            assertEquals(listOf("PATCH"), writes.map { it.method })
            assertTrue(journal.exists())
        }

    @Test
    fun `a replacement row on the original account cannot inherit the recorder row proof`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            override = { request -> if (request.method == "DELETE") MockResponse(code = 204) else null }
            recorder.record(title).join()
            val replacement = JsonObject(checkNotNull(values) + ("updated_at" to JsonPrimitive("2026-11-25T10:02:00Z")))
            values = replacement
            override = { null }

            recorder.remove().join()
            val stopped = recorder.state.value as Recording.Stopped
            assertEquals("Unresolved", stopped.list.name)
            assertEquals(listOf("PATCH", "PATCH", "DELETE"), writes.map { it.method })
            assertTrue(journal.exists())
            assertEquals(replacement, values)
        }

    @Test
    fun `an add acknowledgement and read-back cannot disagree on score or authorize edit or cleanup`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            val changed = JsonObject(planned + ("score" to JsonPrimitive(8)) + ("updated_at" to JsonPrimitive(CHANGED)))
            override = { request ->
                if (request.method == "PATCH") {
                    values = changed
                    json("""{"status":"plan_to_read","score":0}""")
                } else {
                    null
                }
            }
            recorder.record(title).join()

            assertConflict(recorder, "manga_add_read", listOf("PATCH"))
            assertEquals(changed, values)
        }

    @Test
    fun `an edit and read-back with matching fields but different versions cannot authorize cleanup`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            override = { request ->
                if (request.method == "PATCH" && form(request)["status"] == "reading") {
                    values =
                        Json
                            .parseToJsonElement(
                                """{"status":"reading","score":7,"num_chapters_read":1,"num_volumes_read":1,"updated_at":"$CHANGED"}""",
                            ).jsonObject
                    json("""{"status":"reading","score":7,"num_chapters_read":1,"num_volumes_read":1,"updated_at":"$UPDATED"}""")
                } else {
                    null
                }
            }
            recorder.record(title).join()

            values = planned
            assertConflict(recorder, "manga_edit_read", listOf("PATCH", "PATCH"))
            assertEquals(planned, values)
        }

    @Test
    fun `a row changed after the add checkpoint cannot be edited even if the prior version later returns`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            var reads = 0
            override = { request ->
                if (request.url.encodedPath.endsWith("/mangalist") && writes.size == 1 && ++reads == 2) {
                    values = JsonObject(planned + ("updated_at" to JsonPrimitive(CHANGED)))
                }
                null
            }
            recorder.record(title).join()

            values = planned
            assertConflict(recorder, "manga_edit_before", listOf("PATCH"))
            assertEquals(planned, values)
        }

    @Test
    fun `a failed later page cannot restore proof after an early pre-edit mismatch`() =
        runBlocking { interruptedContinuity(500, Halt.Unexpected(500)) }

    @Test
    fun `expiry on a later page cannot restore proof after an early pre-edit mismatch`() =
        runBlocking { interruptedContinuity(401, Halt.NotSignedIn) }

    private suspend fun interruptedContinuity(
        laterStatus: Int,
        halt: Halt,
    ) {
        val recorder = recorder()
        val title = checked(recorder)
        var reads = 0
        fixture.answer = { TokenReply.Answer(400, "") }
        override = { request ->
            when {
                !request.url.encodedPath.endsWith("/mangalist") -> null
                request.url.queryParameter("offset") == "1" -> MockResponse(code = laterStatus)
                writes.size == 1 && ++reads == 2 ->
                    json(
                        """{"data":[{"node":{"id":$ID},"list_status":{"status":"plan_to_read","updated_at":"$CHANGED"}}],""" +
                            """"paging":{"next":"https://invalid.test/?offset=1"}}""",
                    )
                else -> null
            }
        }
        recorder.record(title).join()

        val stopped = recorder.state.value as Recording.Stopped
        assertEquals(halt, stopped.halt)
        assertEquals(MANGA_BEFORE_EDIT, stopped.at)
        assertEquals(ListAfter.Unresolved, stopped.list)
        assertEquals(listOf("PATCH"), writes.map { it.method })

        values = planned
        override = { null }
        if (laterStatus == 401) session.signIn(TokenGrant(ACCESS, "owner-returned"))
        val reopened = recorder()
        assertEquals(ListAfter.Unresolved, (reopened.state.value as Recording.Leftover).list)
        reopened.recheck().join()
        reopened.remove().join()
        assertEquals(ListAfter.Unresolved, (reopened.state.value as Recording.Leftover).list)
        assertEquals(listOf("PATCH"), writes.map { it.method })
        assertTrue(journal.exists())
    }

    @Test
    fun `checking again after a recovery mismatch is read only even when the exact row returns`() =
        runBlocking {
            val recorder = recorder()
            override = { request -> if (request.method == "DELETE") MockResponse(code = 204) else null }
            recorder.record(checked(recorder)).join()
            val verified = checkNotNull(values)
            values = JsonObject(verified + ("updated_at" to JsonPrimitive(CHANGED)))
            override = { null }
            recorder.remove().join()
            assertEquals(ListAfter.Unresolved, (recorder.state.value as Recording.Stopped).list)
            val before = writes.size

            override = { request ->
                if (request.url.encodedPath.endsWith("/mangalist")) response(500, """{"error":"server_error"}""") else null
            }
            recorder.recheck().join()
            assertEquals(ListAfter.Unresolved, (recorder.state.value as Recording.Stopped).list)
            assertEquals(before, writes.size)

            values = verified
            override = { null }
            recorder.recheck().join()
            assertEquals(ListAfter.Left, (recorder.state.value as Recording.Stopped).list)
            assertEquals(before, writes.size)
            val reopened = recorder()
            assertEquals(ListAfter.Left, (reopened.state.value as Recording.Leftover).list)
            assertEquals(before, writes.size)

            reopened.remove().join()
            assertEquals(ListAfter.Removed, (reopened.state.value as Recording.Leftover).list)
            assertEquals(before + 1, writes.size)
        }

    @Test
    fun `proof from an older manga journal is verify only`() =
        runBlocking {
            val recorder = recorder()
            override = { request -> if (request.method == "DELETE") MockResponse(code = 204) else null }
            recorder.record(checked(recorder)).join()
            assertEquals(ListAfter.Left, (recorder.state.value as Recording.Stopped).list)
            journal.writeText(journal.readText().replace("manga_row_v2", "manga_row"))
            override = { null }
            val before = writes.size

            val reopened = recorder()
            assertEquals(ListAfter.Unresolved, (reopened.state.value as Recording.Leftover).list)
            reopened.recheck().join()
            assertEquals(ListAfter.Unresolved, (reopened.state.value as Recording.Leftover).list)
            assertEquals(before, writes.size)
        }

    @Test
    fun `a failed intent unlink preserves the first patch refusal and its stage`() =
        runBlocking {
            val recorder =
                recorder { file, contents ->
                    if (contents == "429\n") {
                        val intent = journal.readBytes()
                        check(journal.delete())
                        check(journal.mkdir())
                        journal.resolve("intent").writeBytes(intent)
                        throw IOException("fixture failed to save refusal")
                    }
                    file.writeText(contents)
                }
            val title = checked(recorder)
            override = { request -> if (request.method == "PATCH") MockResponse(code = 429) else null }
            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.Refused(429), stopped.halt)
            assertEquals("manga_add", stopped.at)
            assertEquals(Halt.JournalRetained, stopped.cleanupHalt)
            assertEquals(ListAfter.Unresolved, stopped.list)
            assertTrue(journal.resolve("intent").isFile)
            assertEquals("PATCH", requests.last().method)
            assertEquals(listOf("PATCH"), writes.map { it.method })

            assertTrue(checkNotNull(stopped.folder).isDirectory)
            val requestCount = requests.size
            recorder.recheck().join()
            assertEquals(stopped.copy(cleanupHalt = Halt.JournalUnreadable), recorder.state.value)
            assertEquals(requestCount, requests.size)
        }

    private suspend fun assertConflict(
        recorder: SignedInRecorder,
        step: String,
        expectedWrites: List<String>,
    ) {
        val stopped = recorder.state.value as Recording.Stopped
        assertEquals(Halt.Unconfirmed, stopped.halt)
        assertEquals(step, stopped.at)
        assertEquals(ListAfter.Unresolved, stopped.list)
        assertEquals(expectedWrites, writes.map { it.method })

        val reopened = recorder()
        assertEquals(ListAfter.Unresolved, (reopened.state.value as Recording.Leftover).list)
        reopened.recheck().join()
        assertEquals(ListAfter.Unresolved, (reopened.state.value as Recording.Leftover).list)
        reopened.remove().join()
        assertEquals(expectedWrites, writes.map { it.method })
        assertTrue(journal.exists())
    }

    @Test
    fun `an invalid journal blocks both scenarios and is never replaced after confirmation`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            val invalid = """{"id":478,"episodes":0,"name":"Fixture title","user":"unknown","media":"unknown"}"""
            journal.writeText(invalid)

            recorder.record(title).join()
            assertTrue(recorder.state.value is Recording.NotStarted)
            assertEquals(invalid, journal.readText())
            val requestCount = requests.size
            recorder.check(ID, RecorderMedia.Manga).join()
            recorder.check(ID).join()
            recorder.recordReads(ReadCapture.MangaList).join()
            assertEquals(requestCount, requests.size)
            assertEquals(emptyList<RecordedRequest>(), writes)
            assertEquals(invalid, journal.readText())

            check(journal.delete())
            check(journal.mkdir())
            recorder.check(ID, RecorderMedia.Manga).join()
            assertTrue(recorder.state.value is Recording.NotStarted)
            assertTrue(journal.isDirectory)
        }

    @Test
    fun `contradictory membership or invalid media type cannot authorize a patch`() =
        runBlocking {
            val recorder = recorder()
            for (invalid in listOf(json(CONTRADICTORY), response(200, EMPTY_LIST, "text/html"))) {
                override = { null }
                val title = checked(recorder)
                override = { request -> invalid.takeIf { request.url.encodedPath.endsWith("/mangalist") } }

                recorder.record(title).join()

                assertEquals(Halt.Unexpected(200), (recorder.state.value as Recording.Stopped).halt)
                assertEquals(emptyList<RecordedRequest>(), writes)
                assertFalse(journal.exists())
            }
        }

    @Test
    fun `an error envelope after delete cannot clear recovery or publish a marker`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            override = { request ->
                if (request.url.encodedPath.endsWith("/mangalist") && writes.any { it.method == "DELETE" }) json(CONTRADICTORY) else null
            }

            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(MANGA_REMOVED_READ, stopped.at)
            assertEquals(ListAfter.Left, stopped.list)
            assertTrue(journal.exists())
            assertFalse(checkNotNull(stopped.folder).resolve(MANGA_CAPTURE_COMPLETE).exists())
            recorder.remove().join()
            assertTrue(journal.exists())
            assertEquals(listOf("PATCH", "PATCH", "DELETE"), writes.map { it.method })
        }

    @Test
    fun `expiry discards the affected staging directory and preserves the proved recovery and older completed run`() =
        runBlocking {
            val recorder = recorder()
            recorder.record(checked(recorder)).join()
            val earlier = (recorder.state.value as Recording.Recorded).folder
            val title = checked(recorder)
            fixture.answer = { TokenReply.Answer(400, "") }
            override = { request ->
                if (request.method == "PATCH" && form(request)["status"] == "reading") MockResponse(code = 401) else null
            }

            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.NotSignedIn, stopped.halt)
            assertEquals(ListAfter.Left, stopped.list)
            assertEquals(null, stopped.folder)
            assertEquals(listOf(earlier), root.listFiles().orEmpty().toList())
            assertTrue(journal.exists())
            validateMangaMutationCapture(earlier)
        }

    @Test
    fun `a refusal takes precedence when saving the reply fails and suppresses further requests until retry after`() =
        runBlocking {
            val recorder =
                recorder { file, contents ->
                    if (contents == "429\n") throw IOException("fixture failure saving refusal")
                    file.writeText(contents)
                }
            val title = checked(recorder)
            override = { request ->
                if (request.method == "PATCH" && form(request)["status"] == "reading") {
                    MockResponse
                        .Builder()
                        .code(429)
                        .addHeader("Retry-After", "1800")
                        .build()
                } else {
                    null
                }
            }

            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.Refused(429), stopped.halt)
            assertEquals(ListAfter.Left, stopped.list)
            assertTrue(journal.exists())
            assertEquals(Duration.ofMinutes(30), network.http.refusedFor())
            val requestCount = requests.size
            assertEquals("PATCH", requests.last().method)
            recorder.remove().join()
            assertEquals(requestCount, requests.size)
            val backingOff = recorder.state.value as Recording.Stopped
            assertEquals(Halt.BackingOff, backingOff.cleanupHalt)
            assertEquals(stopped.folder, backingOff.folder)

            network.clock.now = network.clock.now.plusSeconds(1801)
            override = { null }
            recorder.remove().join()
            val recovered = recorder.state.value as Recording.Stopped
            assertEquals(ListAfter.Removed, recovered.list)
            assertEquals(stopped.folder, recovered.folder)
            assertEquals(stopped.answers, recovered.answers)
            assertFalse(journal.exists())
        }

    @Test
    fun `a portal forbidden response does not start shared back off`() =
        runBlocking {
            val recorder = recorder()
            val title = checked(recorder)
            override = { request -> if (request.method == "PATCH") response(403, "<html>Portal</html>", "text/html") else null }

            recorder.record(title).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.Unexpected(403), stopped.halt)
            assertEquals(Duration.ZERO, network.http.refusedFor())
            assertEquals("Unresolved", stopped.list.name)
            assertEquals(listOf("PATCH"), writes.map { it.method })
        }

    @Test
    fun `marker creation failure keeps a usable partial path and publishes nothing`(): Unit =
        runBlocking {
            val recorder =
                recorder { file, contents ->
                    if (writes.lastOrNull()?.method == "DELETE" && contents.contains("present")) {
                        check(file.parentFile!!.resolve(MANGA_CAPTURE_COMPLETE).mkdir())
                    }
                    file.writeText(contents)
                }
            recorder.record(checked(recorder)).join()

            val stopped = recorder.state.value as Recording.Stopped
            assertEquals(Halt.Storage, stopped.halt)
            assertEquals(ListAfter.Removed, stopped.list)
            assertEquals(MangaRecordingAnswers, stopped.answers)
            val partial = checkNotNull(stopped.folder)
            assertTrue(partial.isDirectory)
            assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("capture-") })
            assertThrows(IllegalStateException::class.java) { validateMangaMutationCapture(partial) }
        }

    private suspend fun recorder(
        pause: suspend () -> Unit = {},
        write: (File, String) -> Unit = { file, contents -> file.writeText(contents) },
    ): SignedInRecorder {
        recorderJob.cancelAndJoin()
        recorderJob = SupervisorJob()
        return SignedInRecorder(
            lazyOf(session),
            network.http,
            { journal },
            { error("Manga answers must stay private") },
            { error("Write recordings must have their own folder") },
            CoroutineScope(recorderJob + Dispatchers.Default),
            pause,
            writePrivateFile = write,
            privateMutations = { root },
        ).also { it.started.join() }
    }

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
                                "list_status" to JsonObject(row + ("updated_at" to (row["updated_at"] ?: JsonPrimitive(UPDATED)))),
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

    private fun json(body: String): MockResponse = response(200, body)

    private fun response(
        code: Int,
        body: String,
        mediaType: String = "application/json",
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", mediaType)
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
        const val UPDATED = "2026-11-25T10:01:00Z"
        const val CHANGED = "2026-11-25T10:02:00Z"
        const val CONTRADICTORY = """{"error":"server_error","data":[],"paging":{}}"""
        const val EMPTY_LIST = """{"data":[],"paging":{}}"""
        const val ACCESS = "manga-test-access"
        const val OTHER = "manga-replacement-test-access"
    }
}
