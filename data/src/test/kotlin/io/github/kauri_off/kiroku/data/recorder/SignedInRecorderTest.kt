package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.CLIENT_ID_HEADER
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.recorder.PlantedMal.Companion.ACCESS
import io.github.kauri_off.kiroku.data.recorder.PlantedMal.Companion.REFRESH
import io.github.kauri_off.kiroku.data.recorder.PlantedMal.Companion.SECRETS
import io.github.kauri_off.kiroku.data.recorder.PlantedMal.Companion.TITLE
import io.github.kauri_off.kiroku.data.recorder.PlantedMal.Companion.USER_ID
import io.github.kauri_off.kiroku.data.recorder.PlantedMal.Companion.answer
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import io.github.kauri_off.kiroku.data.session.TokenReply
import io.github.kauri_off.kiroku.data.session.granted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// Section 11.1: the signed-in recorder stores MAL's answers as sent, without secrets, and the user's list is afterwards as it was.
@RunWith(RobolectricTestRunner::class)
class SignedInRecorderTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    private val fixture by lazy { SessionFixture(folder.newFolder()) }
    private val mal = PlantedMal()
    private val journal by lazy { File(folder.root, "own/journal") }
    private val files by lazy { RecorderFolders(File(folder.root, "recorded"), File(folder.root, "run")) }
    private val privateReads by lazy { File(folder.root, "private/reads") }
    private val foldersAsked = AtomicInteger()
    private val privateReadsAsked = AtomicInteger()
    private val events = CopyOnWriteArrayList<String>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val title = RecordedTitle(TITLE, "A finished title", 12)
    private val listBefore = mal.onTheList()

    @Before
    fun serve() {
        network.api.dispatcher = mal
        mal.arrived = { events += it.substringBefore('?') }
    }

    @After
    fun close() =
        runBlocking {
            scopes.forEach { it.cancel() }
            fixture.close()
        }

    private fun signedIn(lifetime: Long? = LIFETIME): MalSession =
        runBlocking { fixture.open().also { it.signIn(TokenGrant(ACCESS, REFRESH, lifetime)) } }

    private fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    // A recorder as a process makes it: what an earlier run left is dealt with before anything else.
    private fun recorder(
        session: MalSession,
        scope: CoroutineScope = scope(),
        writePrivateFile: (File, String) -> Unit = { file, text -> file.writeText(text) },
        pause: suspend () -> Unit = { events += "pause" },
    ): SignedInRecorder =
        SignedInRecorder(lazyOf(session), network.http, {
            journal
        }, { files.also { foldersAsked.incrementAndGet() } }, {
            privateReads.also { privateReadsAsked.incrementAndGet() }
        }, scope, pause, writePrivateFile = writePrivateFile).also { runBlocking { it.started.join() } }

    private fun SignedInRecorder.checked(id: Long = TITLE): Recording =
        runBlocking {
            check(id).join()
            state.value
        }

    private fun SignedInRecorder.recorded(): Recording =
        runBlocking {
            record((checked() as Recording.Checked).title).join()
            state.value
        }

    private fun File?.texts(): Map<String, String> = checkNotNull(this).listFiles().orEmpty().associate { it.name to it.readText() }

    private fun folders(): Map<String, String> = files.recorded.texts()

    private fun stored(name: String): JsonObject = Json.parseToJsonElement(File(files.recorded, name).readText()).jsonObject

    private fun status(name: String): Int =
        when (name) {
            "score_refused", "status_refused" -> 400
            "removed_again" -> 404
            else -> 200
        }

    private val change = "/v2/anime/$TITLE/my_list_status"
    private val allWrites =
        OwnTitleWrites.map { write -> "PATCH $change ${write.form(12).entries.joinToString("&") { "${it.key}=${it.value}" }}" } +
            listOf("DELETE $change", "DELETE $change")

    // The requests in place of which the test's answer is given: those whose line holds [part].
    private fun instead(
        part: String,
        response: () -> MockResponse,
    ) {
        mal.instead = { request -> if (part in "${request.method} ${request.target} ${request.body?.utf8()}") response() else null }
    }

    @Test
    fun `a recording stores every answer of its list and leaves the user's list as it was`() {
        val recorder = recorder(signedIn())

        assertEquals(Recording.Checked(title), recorder.checked())
        assertEquals(emptyList<String>(), mal.writes)
        val end = recorder.recorded()

        assertEquals(Recording.Recorded(title, SignedInAnswers.size, files.recorded, ListAfter.Same), end)
        assertEquals(SignedInAnswers.map { "$it.${status(it)}.json" }.toSet(), files.recorded.texts().keys)
        assertEquals(allWrites, mal.writes)
        assertEquals(listBefore, mal.onTheList())
        assertTrue(mal.tokens.all { it == ACCESS })
        assertFalse(journal.exists())
        assertFalse(files.run.exists())
        assertEquals(emptyList<Throwable>(), fixture.unexpected)
        assertEquals(emptyList<Throwable>(), network.unexpected)
    }

    @Test
    fun `the requests are the profile, the whole list, three pages of it, the title, the changes each read back, and the removals`() {
        recorder(signedIn()).recorded()

        val entry = "GET /v2/anime/$TITLE"
        val list = "GET /v2/users/@me/animelist"
        val changes = allWrites.dropLast(2).flatMap { listOf(it, entry) }
        assertEquals(
            listOf(entry, "GET /v2/users/@me", list, list, list, list, entry) + changes +
                listOf(entry, "DELETE $change", entry, "DELETE $change", list),
            events.filter { it != "pause" },
        )
        assertEquals(RecordingRequests, events.count { it != "pause" } - 1)
        val pages = mal.asked.filter { "sort=anime_title" in it }.map { it.substringAfter("sort=anime_title") }
        assertEquals(listOf("&limit=14&offset=0", "&limit=14&offset=14", "&limit=14&offset=28"), pages)
        assertTrue(mal.asked.filter { it.startsWith("GET /v2/users/@me/animelist") }.all { "nsfw=true" in it })
    }

    @Test
    fun `every answer is stored as MAL sent it, without a token, the client id or a header`() {
        recorder(signedIn()).recorded()

        val sent = mal.bodies.map { it.replace(ACCESS, "access-token").replace(NetworkFixture.CLIENT_ID, "client-id") }
        val texts = files.recorded.texts()
        for ((name, text) in texts - "$TOKEN_GRANTED.200.json") assertTrue(name, text in sent)
        for ((name, text) in texts) for (secret in SECRETS) assertFalse("$secret in $name", secret in name || secret in text)
        val profile = texts.getValue("profile.200.json")
        for (value in listOf(PlantedMal.USER_NAME, PlantedMal.PICTURE, "$USER_ID", "1991-07-13", "567.89", """"client":"client-id"""")) {
            assertTrue(value, value in profile)
        }
        assertEquals(""""Invalid value, user $USER_ID"""", "${stored("score_refused.400.json")["message"]}")
    }

    @Test
    fun `the pages of the user's own list are stored whole, as MAL sent them`() {
        recorder(signedIn()).recorded()

        val pages = (1..3).map { stored("own_list_$it.200.json") }
        val entries = pages.flatMap { page -> page["data"]!!.jsonArray.map { it.jsonObject } }
        assertEquals(listBefore.keys.toList(), entries.map { it["node"]!!.jsonObject["id"]!!.jsonPrimitive.long })
        assertEquals(""""Listed 101"""", "${entries[0]["node"]!!.jsonObject["title"]}")
        assertEquals(
            """{"status":"completed","score":9,"num_episodes_watched":24,"is_rewatching":false,"updated_at":"${PlantedMal.UPDATED_AT}",""" +
                """"start_date":"2015-04-05","finish_date":"2015-06-21",""" +
                """"comments":"a planted note ${PlantedMal.USER_NAME} access-token","tags":["a planted tag"]}""",
            "${entries[0]["list_status"]}",
        )
        assertEquals(
            """"https://api.myanimelist.net/v2/users/${PlantedMal.USER_NAME}/animelist?token=access-token&limit=14&offset=14"""",
            "${pages[0]["paging"]!!.jsonObject["next"]}",
        )
    }

    @Test
    fun `the values of the recorder's own title are stored as MAL wrote them`() {
        recorder(signedIn()).recorded()

        assertNull(stored("entry_absent.200.json")["my_list_status"])
        assertEquals(""""plan_to_watch"""", "${stored("add_planned.200.json")["status"]}")
        assertEquals(
            """" ${PlantedMal.USER_NAME} access-token" ["a planted tag"]""",
            "${stored("add_planned.200.json")["comments"]} ${stored("add_planned.200.json")["tags"]}",
        )
        assertEquals("\" ${PlantedMal.USER_NAME} no token\"", "${stored("entry_full.200.json")["my_list_status"]!!.jsonObject["comments"]}")
        val scored = stored("score_set_read.200.json")["my_list_status"]!!.jsonObject
        assertEquals(
            """7 11 "2100-01-01" "2020-05"""",
            "${scored["score"]} ${scored["num_episodes_watched"]} ${scored["finish_date"]} ${scored["start_date"]}",
        )
        assertEquals("\"${PlantedMal.CHANGED_AT}\"", "${scored["updated_at"]}")
        assertEquals(""""2020-02-02"""", "${stored("finish_set_read.200.json")["my_list_status"]!!.jsonObject["finish_date"]}")
        assertNull(stored("finish_cleared_read.200.json")["my_list_status"]!!.jsonObject["finish_date"])
        assertEquals(""""bad_request"""", "${stored("score_refused.400.json")["error"]}")
        assertNull(stored("removed_read.200.json")["my_list_status"])
        assertEquals(""""not_found"""", "${stored("removed_again.404.json")["error"]}")
    }

    @Test
    fun `a title that is on the list is refused before any write, whichever answer says so`() {
        val recorder = recorder(signedIn())
        mal.titles += 101L to PlantedMal.Title("Listed 101", 24)

        assertEquals(Recording.NotStarted(Halt.OnTheList), recorder.checked(101))
        assertEquals(listOf("GET /v2/anime/101"), events)

        // The title's own answer keeps quiet, and the list does not.
        mal.tellsTheEntry = false
        val listed = RecordedTitle(101, "Listed 101", 24)
        assertEquals(Recording.Checked(listed), recorder.checked(101))
        runBlocking { recorder.record(listed).join() }

        val end = recorder.state.value as Recording.Stopped
        assertEquals(listOf("entry_absent", Halt.OnTheList, ListAfter.Untouched), listOf(end.at, end.halt, end.list))
        assertEquals(emptyList<String>(), mal.writes)
        assertEquals(listBefore, mal.onTheList())
        assertFalse(journal.exists())
        assertFalse(files.recorded.exists())
        for ((name, text) in files.run.texts()) for (secret in SECRETS) assertFalse("$secret in $name", secret in text)
    }

    @Test
    fun `a title MAL does not know, one that has not ended and one with too few episodes are refused`() {
        val recorder = recorder(signedIn())
        mal.titles +=
            mapOf(
                6L to PlantedMal.Title("On air", 12, status = "currently_airing"),
                7L to PlantedMal.Title("Short", 2),
                8L to PlantedMal.Title("Unknown count", 0),
            )

        assertEquals(Recording.NotStarted(Halt.NoSuchTitle), recorder.checked(9))
        assertEquals(Recording.NotStarted(Halt.NotFinished), recorder.checked(6))
        assertEquals(Recording.NotStarted(Halt.TooFewEpisodes), recorder.checked(7))
        assertEquals(Recording.NotStarted(Halt.TooFewEpisodes), recorder.checked(8))
        assertEquals(emptyList<String>(), mal.writes)
    }

    @Test
    fun `nothing is asked or written without a check of that title that is still shown`() {
        val recorder = recorder(signedIn())

        runBlocking { recorder.record(title).join() }
        assertEquals(emptyList<String>(), events)

        recorder.checked()
        runBlocking { recorder.record(title.copy(id = 101)).join() }
        recorder.dismiss()
        assertEquals(Recording.Idle, recorder.state.value)
        runBlocking { recorder.record(title).join() }

        assertEquals(listOf("GET /v2/anime/$TITLE"), events)
        assertEquals(Recording.Idle, recorder.state.value)
    }

    @Test
    fun `the title is on the disk before the first write, and without it nothing is written`() {
        val journals = mutableListOf<String?>()
        mal.arrived =
            { line ->
                if (line.startsWith("PATCH") ||
                    line.startsWith("DELETE")
                ) {
                    journals += journal.takeIf { it.exists() }?.readText()
                }
            }
        val session = signedIn()
        recorder(session).recorded()
        val atTheAdd = journals.first()!!
        assertTrue(atTheAdd, """"id":$TITLE""" in atTheAdd && "A finished title" in atTheAdd)
        assertFalse(atTheAdd, "$USER_ID" in atTheAdd || PlantedMal.USER_NAME in atTheAdd)
        // It is there until MAL has answered the removal.
        assertEquals(allWrites.size - 1, journals.count { it != null })

        mal.asked.clear()
        journal.parentFile!!.deleteRecursively()
        journal.parentFile!!.writeText("no folder can be made here")
        assertEquals(Recording.NotStarted(Halt.JournalUnreadable), recorder(session).checked())
        assertEquals(emptyList<String>(), mal.asked)
        assertEquals(emptyList<String>(), mal.writes)
    }

    @Test
    fun `a run that ended with its process takes its title off the list at the next start`() {
        val process = scope()
        val dying =
            recorder(signedIn(), process) {
                if (mal.writes.isNotEmpty()) {
                    process.cancel()
                    awaitCancellation()
                }
            }
        dying.recorded()
        assertEquals(listOf(allWrites.first()), mal.writes)
        assertTrue(TITLE in mal.onTheList())
        assertTrue(journal.exists())

        val next = recorder(runBlocking { fixture.reopen() })

        assertEquals(Recording.Leftover(title, ListAfter.Removed, null), next.state.value)
        assertEquals(listOf(allWrites.first(), "DELETE $change"), mal.writes)
        assertEquals(listBefore, mal.onTheList())
        assertFalse(journal.exists())
        assertFalse(files.recorded.exists())

        // A start with nothing left asks nothing, of MAL or of the device's shared storage.
        mal.asked.clear()
        foldersAsked.set(0)
        assertEquals(Recording.Idle, recorder(runBlocking { fixture.reopen() }).state.value)
        assertEquals(emptyList<String>(), mal.asked)
        assertEquals(0, foldersAsked.get())
    }

    @Test
    fun `a run stops at the first answer it does not expect, takes its title off and replaces no stored answer`() {
        files.recorded.mkdirs()
        File(files.recorded, "score_set.200.json").writeText("an earlier run's")
        files.run.mkdirs()
        File(files.run, "left_by_a_stopped_run.200.json").writeText("an earlier run's")
        instead("score=7") { answer(500, """{"error":"internal"}""") }

        val end = recorder(signedIn()).recorded() as Recording.Stopped

        assertEquals(listOf("score_set", Halt.Unexpected(500), ListAfter.Removed), listOf(end.at, end.halt, end.list))
        val sent = allWrites.takeWhile { "score=7" !in it } + allWrites.first { "score=7" in it }
        assertEquals(sent + "DELETE $change", mal.writes)
        assertEquals(listOf("GET /v2/users/@me?fields=id", "DELETE $change"), mal.asked.takeLast(3).take(2))
        assertTrue(mal.asked.last().startsWith("GET /v2/anime/$TITLE?"))
        assertEquals(listBefore, mal.onTheList())
        assertFalse(journal.exists())
        assertEquals(mapOf("score_set.200.json" to "an earlier run's"), files.recorded.texts())
        assertEquals(end.answers, files.run.texts().size)
        assertFalse("left_by_a_stopped_run.200.json" in files.run.texts())
        assertEquals(files.run, end.folder)
        assertTrue("score_set.500.json" in files.run.texts())
        assertFalse(
            files.run
                .texts()
                .values
                .any { text -> SECRETS.any(text::contains) },
        )
    }

    @Test
    fun `after MAL's refusal nothing more is sent, and the title is taken off at the user's word`() {
        instead("score=7") { answer(429, """{"error":"too_many_requests"}""") }
        val recorder = recorder(signedIn())

        val end = recorder.recorded() as Recording.Stopped

        assertEquals(listOf("score_set", Halt.Refused(429), ListAfter.Left), listOf(end.at, end.halt, end.list))
        assertTrue(mal.asked.last().endsWith("score=7"))
        assertTrue(TITLE in mal.onTheList())
        assertTrue("score_set.429.json" in files.run.texts())

        // While the title is left, nothing new starts and the report stays.
        recorder.dismiss()
        assertEquals(end, recorder.state.value)
        val asked = mal.asked.size
        assertEquals(Recording.Leftover(title, ListAfter.Left, null), recorder.checked())
        assertEquals(Recording.Leftover(title, ListAfter.Left, null), recorder.captured(ReadCapture.MangaList))
        assertEquals(asked, mal.asked.size)

        mal.instead = { null }
        runBlocking { recorder.remove().join() }

        assertEquals(Recording.Leftover(title, ListAfter.Removed, null), recorder.state.value)
        assertEquals(listBefore, mal.onTheList())
        assertFalse(journal.exists())
        recorder.dismiss()
        assertEquals(Recording.Idle, recorder.state.value)
    }

    @Test
    fun `after a request without an answer nothing more is sent, and the next start takes the title off`() {
        instead("score=7") { NetworkFixture.dropped() }
        val session = signedIn()

        val end = recorder(session).recorded() as Recording.Stopped

        assertEquals(listOf("score_set", Halt.NoAnswer, ListAfter.Left), listOf(end.at, end.halt, end.list))
        assertTrue(mal.asked.last().endsWith("score=7"))

        // A start while MAL still does not answer leaves the title where it is, and says so.
        instead("users/@me") { NetworkFixture.dropped() }
        assertEquals(Recording.Leftover(title, ListAfter.Left, Halt.NoAnswer), recorder(session).state.value)
        assertTrue(journal.exists())

        mal.instead = { null }
        assertEquals(Recording.Leftover(title, ListAfter.Removed, null), recorder(session).state.value)
        assertEquals(listBefore, mal.onTheList())
    }

    @Test
    fun `a token MAL no longer takes ends the run, and the title waits for a sign-in`() {
        fixture.answer = { TokenReply.Answer(401, "") }
        mal.arrived = { if (it.endsWith("score=7")) mal.valid = emptySet() }
        val session = signedIn()

        val end = recorder(session).recorded() as Recording.Stopped

        assertEquals(listOf("score_set", Halt.NotSignedIn, ListAfter.Left), listOf(end.at, end.halt, end.list))
        assertEquals(1, fixture.grants.size)
        assertEquals(Recording.Leftover(title, ListAfter.Left, Halt.NotSignedIn), recorder(session).state.value)
        assertTrue(journal.exists())
        assertTrue(mal.asked.last().endsWith("score=7"))
    }

    @Test
    fun `the list of another user than the one the recording was made as is not touched`() {
        instead("score=7") { answer(429, "{}") }
        val session = signedIn()
        recorder(session).recorded()
        mal.instead = { null }
        mal.user = USER_ID + 1
        val writes = mal.writes.size

        assertEquals(Recording.Leftover(title, ListAfter.Left, Halt.AnotherAccount), recorder(session).state.value)

        assertEquals(writes, mal.writes.size)
        assertTrue(journal.exists())
    }

    @Test
    fun `a removal at the next start that MAL fails to make leaves the journal and does not say removed`() {
        instead("score=7") { answer(429, "{}") }
        val session = signedIn()
        recorder(session).recorded()
        instead("DELETE") { answer(500, """{"error":"internal"}""") }

        assertEquals(Recording.Leftover(title, ListAfter.Left, Halt.Unexpected(500)), recorder(session).state.value)

        assertTrue(journal.exists())
        assertTrue(TITLE in mal.onTheList())
    }

    @Test
    fun `a write MAL answers with 403 stops the run, and nothing more is sent`() {
        instead("score=7") { answer(403, """{"error":"forbidden"}""") }

        val end = recorder(signedIn()).recorded() as Recording.Stopped

        assertEquals(listOf("score_set", Halt.Refused(403), ListAfter.Left), listOf(end.at, end.halt, end.list))
        assertTrue(mal.asked.last().endsWith("score=7"))
        assertTrue(journal.exists())
    }

    @Test
    fun `the journal stays while MAL still shows the title after its removal, in a run and at a start`() {
        mal.removalsDoNothing = true
        val session = signedIn()
        val recorder = recorder(session)

        val end = recorder.recorded() as Recording.Stopped

        assertEquals(listOf("removed_read", Halt.StillOnTheList, ListAfter.Left), listOf(end.at, end.halt, end.list))
        assertEquals(1, mal.writes.count { it.startsWith("DELETE") })
        assertTrue(journal.exists())
        assertFalse(files.recorded.exists())

        assertEquals(Recording.Leftover(title, ListAfter.Left, Halt.StillOnTheList), recorder(session).state.value)
        assertTrue(journal.exists())

        mal.removalsDoNothing = false
        runBlocking { recorder.remove().join() }
        assertEquals(Recording.Leftover(title, ListAfter.Removed, null), recorder.state.value)
        assertTrue(mal.asked.last().startsWith("GET /v2/anime/$TITLE?"))
        assertFalse(journal.exists())
        assertEquals(listBefore, mal.onTheList())
    }

    @Test
    fun `a title the whole list still holds after the recording is journalled again and can be removed`() {
        val recorder = recorder(signedIn())
        mal.arrived = { if (it.startsWith("DELETE")) mal.listStillShows = setOf(TITLE) }

        val end = recorder.recorded()

        assertEquals(Recording.Recorded(title, SignedInAnswers.size, files.recorded, ListAfter.Left), end)
        assertTrue(journal.exists())
        recorder.dismiss()
        assertEquals(end, recorder.state.value)

        mal.listStillShows = emptySet()
        runBlocking { recorder.remove().join() }
        assertEquals(Recording.Leftover(title, ListAfter.Removed, null), recorder.state.value)
        assertFalse(journal.exists())
    }

    @Test
    fun `the whole list is read page after page until MAL names no next one`() {
        mal.largestPage = 15

        val end = recorder(signedIn()).recorded()

        assertEquals(Recording.Recorded(title, SignedInAnswers.size, files.recorded, ListAfter.Same), end)
        val whole = mal.asked.filter { "animelist?nsfw=true" in it }.map { it.substringAfter("&offset=") }
        assertEquals(listOf("0", "15", "30", "0", "15", "30"), whole)
    }

    @Test
    fun `a title on the last page of the list is refused before any write`() {
        mal.largestPage = 15
        mal.titles += 500L to PlantedMal.Title("On the last page", 12)
        val last = RecordedTitle(500, "On the last page", 12)
        val recorder = recorder(signedIn())
        assertEquals(Recording.Checked(last), recorder.checked(500))
        mal.addedElsewhere(500)
        mal.tellsTheEntry = false

        runBlocking { recorder.record(last).join() }

        val end = recorder.state.value as Recording.Stopped
        assertEquals(listOf("entry_absent", Halt.OnTheList, ListAfter.Untouched), listOf(end.at, end.halt, end.list))
        assertEquals(emptyList<String>(), mal.writes)
    }

    @Test
    fun `a list whose next page cannot be found is no list to check against, and nothing is written`() {
        mal.largestPage = 15
        mal.brokenNext = true

        val end = recorder(signedIn()).recorded() as Recording.Stopped

        assertEquals(listOf("count", Halt.Unexpected(200), ListAfter.Untouched), listOf(end.at, end.halt, end.list))
        assertEquals(emptyList<String>(), mal.writes)
        assertFalse(journal.exists())
    }

    @Test
    fun `the title's own answer is asked again just before the first write, whatever the list says`() {
        val recorder = recorder(signedIn())
        val checked = recorder.checked() as Recording.Checked
        mal.addedElsewhere(TITLE)
        mal.listHides = setOf(TITLE)

        runBlocking { recorder.record(checked.title).join() }

        val end = recorder.state.value as Recording.Stopped
        assertEquals(listOf("entry_absent", Halt.OnTheList, ListAfter.Untouched), listOf(end.at, end.halt, end.list))
        assertEquals(emptyList<String>(), mal.writes)
        assertFalse(journal.exists())
    }

    @Test
    fun `an add MAL refuses ends the run there, and the title is taken off in case it was added`() {
        instead("status=plan_to_watch") { answer(400, """{"error":"bad_request"}""") }

        val end = recorder(signedIn()).recorded() as Recording.Stopped

        assertEquals(listOf("add_planned", Halt.Unexpected(400), ListAfter.Removed), listOf(end.at, end.halt, end.list))
        assertEquals(listOf(allWrites.first(), "DELETE $change"), mal.writes)
        assertFalse(journal.exists())
    }

    @Test
    fun `a file that cannot be stored after the add ends the run, and the title is taken off at once`() {
        val recorder = recorder(signedIn())
        mal.arrived = {
            if (it.startsWith("PATCH")) {
                files.run.deleteRecursively()
                files.run.writeText("no folder can be made here")
            }
        }

        val end = recorder.recorded() as Recording.Stopped

        assertEquals(listOf("add_planned", Halt.Storage, ListAfter.Removed), listOf(end.at, end.halt, end.list))
        assertEquals(listOf(allWrites.first(), "DELETE $change"), mal.writes)
        assertEquals(listBefore, mal.onTheList())
        assertFalse(journal.exists())
    }

    @Test
    fun `the journal's rename is synced in its folder once the journal is in its place`() {
        val seen = mutableListOf<String>()
        val file = File(folder.root, "kept/journal")

        Journal(file) { seen += "${it.name}, journal there: ${file.exists()}" }.write(Journal.Left(title, "user"))

        assertEquals(listOf("kept, journal there: true"), seen)
        assertEquals(TITLE, Journal(file).read()!!.title.id)
        syncedDirectory(file.parentFile!!)
    }

    @Test
    fun `a read-back that is not MAL's own answer for the title keeps the journal`() {
        instead("score=7") { answer(429, "{}") }
        val session = signedIn()
        recorder(session).recorded()
        mal.removalsDoNothing = true
        val readBack = "anime/$TITLE?fields=id,num_episodes"

        instead(readBack) { answer(200, "<html>Maintenance</html>", "text/html") }
        assertEquals(Recording.Leftover(title, ListAfter.Left, Halt.Unexpected(200)), recorder(session).state.value)
        instead(readBack) { answer(200, """{"id":6,"num_episodes":12}""") }
        assertEquals(Recording.Leftover(title, ListAfter.Left, Halt.Unexpected(200)), recorder(session).state.value)
        instead(readBack) { answer(404, "<html>Not found</html>", "text/html") }
        assertEquals(Recording.Leftover(title, ListAfter.Left, Halt.Unexpected(404)), recorder(session).state.value)

        assertTrue(journal.exists())
        assertTrue(TITLE in mal.onTheList())
    }

    @Test
    fun `a title MAL says it no longer knows leaves nothing to remove, and the journal goes`() {
        instead("score=7") { answer(429, "{}") }
        val session = signedIn()
        recorder(session).recorded()
        instead("anime/$TITLE") { answer(404, PlantedMal.NOT_FOUND) }

        assertEquals(Recording.Leftover(title, ListAfter.Removed, null), recorder(session).state.value)

        assertFalse(journal.exists())
        assertEquals(Recording.Idle, recorder(session).state.value)
    }

    @Test
    fun `requests go one at a time with a pause between them`() {
        val recorder = recorder(signedIn())
        recorder.checked()
        assertEquals(1, events.size)
        events.clear()

        runBlocking { recorder.record(title).join() }

        assertTrue(events.size > 2 * SignedInAnswers.size - 4)
        assertEquals(events.indices.map { it % 2 == 1 }, events.map { it == "pause" })
    }

    @Test
    fun `a token MAL rejects goes back to the session, which refreshes it once, and no token is stored`() {
        fixture.answer = { granted("planted-access-token-2", "planted-refresh-token-2") }
        mal.valid = setOf("planted-access-token-2")

        val end = recorder(signedIn()).recorded()

        assertEquals(Recording.Recorded(title, SignedInAnswers.size, files.recorded, ListAfter.Same), end)
        assertEquals(1, fixture.grants.size)
        assertEquals(listOf(ACCESS, "planted-access-token-2"), mal.tokens.distinct())
        assertEquals(1, mal.tokens.count { it == ACCESS })
        assertFalse(
            files.recorded
                .texts()
                .values
                .any { "planted-access-token" in it || "planted-refresh-token" in it },
        )
    }

    @Test
    fun `an answer without a body is stored empty and stops nothing`() {
        mal.answersRemovalsInJson = false

        val end = recorder(signedIn()).recorded()

        assertEquals(Recording.Recorded(title, SignedInAnswers.size, files.recorded, ListAfter.Same), end)
        assertEquals("", files.recorded.texts()["removed.200.txt"])
    }

    @Test
    fun `of a token answer the lifetime MAL gave the access token is stored, and nothing else`() {
        recorder(signedIn()).recorded()

        assertEquals("{\n    \"expires_in\": $LIFETIME\n}\n", folders().getValue("$TOKEN_GRANTED.200.json"))
    }

    @Test
    fun `when MAL gave the token no lifetime, none is stored and the run goes on`() {
        val end = recorder(signedIn(lifetime = null)).recorded()

        assertEquals(Recording.Recorded(title, SignedInAnswers.size - 1, files.recorded, ListAfter.Same), end)
        assertFalse(
            files.recorded
                .texts()
                .keys
                .any { it.startsWith(TOKEN_GRANTED) },
        )
    }

    @Test
    fun `nobody signed in is a stop before anything is asked of MAL`() {
        val recorder = recorder(runBlocking { fixture.open() })

        assertEquals(Recording.NotStarted(Halt.NotSignedIn), recorder.checked())
        assertEquals(emptyList<String>(), mal.asked)
    }

    @Test
    fun `a list that holds other titles after the recording is said to differ`() {
        val recorder = recorder(signedIn())
        mal.arrived = { if (it.startsWith("DELETE")) mal.addedElsewhere(300) }

        assertEquals(Recording.Recorded(title, SignedInAnswers.size, files.recorded, ListAfter.Differs), recorder.recorded())
    }

    @Test
    fun `suggestions captures the returned offset with bearer only and removes secrets from both pages`() {
        val first = pagingBody("https://example.invalid/another?offset=17&limit=99&client_id=${NetworkFixture.CLIENT_ID}")
        val second = pagingBody("https://example.invalid/another?offset=30&access_token=$ACCESS")
        answerPages(first, second)

        val end = recorder(signedIn()).captured(ReadCapture.Suggestions) as Recording.ReadRecorded

        assertEquals(2, end.answers)
        assertEquals(
            mapOf(
                "suggestions_first.200.json" to first.replace(NetworkFixture.CLIENT_ID, "client-id"),
                "suggestions_next.200.json" to second.replace(ACCESS, "access-token"),
            ),
            end.folder.texts(),
        )
        for (offset in listOf("0", "17")) {
            val url = readRequest("/v2/anime/suggestions", offset)
            assertEquals(setOf("fields", "limit", "offset"), url.queryParameterNames)
            assertEquals(MalFields.SUGGESTIONS, url.queryParameter("fields"))
            assertEquals("10", url.queryParameter("limit"))
        }
        assertEquals(2, network.api.requestCount)
        assertEquals(listOf("GET /v2/anime/suggestions", "pause", "GET /v2/anime/suggestions"), events)
        assertFalse(journal.exists())
        assertFalse(files.recorded.exists())
        assertFalse(files.run.exists())
    }

    @Test
    fun `private JSON redacts escaped keys and encoded URLs while retaining page contents`() {
        val client = NetworkFixture.CLIENT_ID
        val encodedClient = "%25%37%34${client.drop(1)}"
        val encodedAccess = ACCESS.replace("-", "%2D")
        val first = pagingBody("https://example.invalid/$encodedClient?offset=17&$encodedClient=$encodedAccess&label=a%26b")
        val escapedAccess = "\\u0070${ACCESS.drop(1)}"
        val nestedAccess = "%25%37%30${ACCESS.drop(1)}"
        val second =
            """{"data":[{"$escapedAccess":"$escapedAccess","access-token":"kept","$nestedAccess":"$nestedAccess"}],"paging":{}}"""
        answerPages(first, second)

        val end = recorder(signedIn()).captured(ReadCapture.Suggestions) as Recording.ReadRecorded

        val pages = end.folder.texts()
        val storedFirst = Json.parseToJsonElement(pages.getValue("suggestions_first.200.json")).jsonObject
        val next =
            storedFirst
                .getValue("paging")
                .jsonObject
                .getValue("next")
                .jsonPrimitive.content
        assertEquals("https://example.invalid/client-id?offset=17&client-id=access-token&label=a%26b", next)
        assertEquals(Json.parseToJsonElement(first).jsonObject["data"], storedFirst["data"])
        val storedSecond = Json.parseToJsonElement(pages.getValue("suggestions_next.200.json")).jsonObject
        val entry =
            storedSecond
                .getValue("data")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(
            mapOf(
                "access-token-2" to JsonPrimitive("access-token"),
                "access-token" to JsonPrimitive("kept"),
                "access-token-3" to JsonPrimitive("access-token"),
            ),
            entry,
        )
        val decodedTwice = URLDecoder.decode(URLDecoder.decode(storedSecond.toString(), "UTF-8"), "UTF-8")
        assertFalse(ACCESS in decodedTwice)
        network.api.received()
        val secondRequest = network.api.received()
        assertEquals("17", secondRequest.url.queryParameter("offset"))
        assertTrue(network.log.none { ACCESS in it || client in it || "a%26b" in it || "kept" in it })
    }

    @Test
    fun `manga captures every page and a later empty capture keeps earlier answers separate`() {
        val pages =
            listOf(
                pagingBody("https://example.invalid/?offset=2", count = 2),
                pagingBody("https://example.invalid/?offset=4", count = 2),
                pagingBody(count = 1),
            )
        val empty = pagingBody(count = 0)
        answerPages(*(pages + empty).toTypedArray())
        val recorder = recorder(signedIn())

        val whole = recorder.captured(ReadCapture.MangaList) as Recording.ReadRecorded
        val single = recorder.captured(ReadCapture.MangaList) as Recording.ReadRecorded

        assertEquals(3, whole.answers)
        assertEquals(1, single.answers)
        assertEquals(File(privateReads, "manga_list"), whole.folder.parentFile)
        assertEquals(whole.folder.parentFile, single.folder.parentFile)
        assertEquals(pages.mapIndexed { index, body -> "manga_list_${index + 1}.200.json" to body }.toMap(), whole.folder.texts())
        assertEquals(mapOf("manga_list_1.200.json" to empty), single.folder.texts())
        for (offset in listOf("0", "2", "4", "0")) {
            val url = readRequest("/v2/users/@me/mangalist", offset)
            assertEquals(setOf("fields", "nsfw", "sort", "limit", "offset"), url.queryParameterNames)
            assertEquals(
                "id,title,main_picture,alternative_titles,media_type,status,num_chapters,num_volumes," +
                    "list_status{status,score,num_chapters_read,num_volumes_read,updated_at}",
                url.queryParameter("fields"),
            )
            assertEquals("true", url.queryParameter("nsfw"))
            assertEquals("manga_title", url.queryParameter("sort"))
            assertEquals("2", url.queryParameter("limit"))
        }
        assertFalse(journal.exists())
    }

    @Test
    fun `a refused continuation leaves a status and a partial run while the earlier capture stays`() {
        val terminal = pagingBody()
        val first = pagingBody("https://example.invalid/?offset=17")
        val responses = ArrayDeque(listOf(answer(200, terminal), answer(200, first), answer(429, "<html>$ACCESS</html>", "text/html")))
        mal.instead = { responses.removeFirst() }
        val recorder = recorder(signedIn())

        val whole = recorder.captured(ReadCapture.Suggestions) as Recording.ReadRecorded
        val failed = recorder.captured(ReadCapture.Suggestions) as Recording.ReadStopped

        assertEquals(mapOf("suggestions_first.200.json" to terminal), whole.folder.texts())
        assertEquals("suggestions_next", failed.at)
        assertEquals(Halt.Refused(429), failed.halt)
        assertEquals(2, failed.answers)
        assertEquals(File(privateReads, "suggestions"), failed.folder?.parentFile)
        assertEquals(mapOf("suggestions_first.200.json" to first, "suggestions_next.429.txt" to "429\n"), failed.folder.texts())
        assertEquals(3, network.api.requestCount)
        assertTrue(mal.writes.isEmpty())
    }

    @Test
    fun `a stopped path stays bound to its capture after a replacement sign-in`() {
        val session = signedIn()
        val recorder = recorder(session)
        mal.instead = { answer(429, "") }
        val first = recorder.captured(ReadCapture.Suggestions) as Recording.ReadStopped

        runBlocking { session.signIn(TokenGrant("replacement-test-access", "replacement-test-refresh")) }
        mal.instead = { answer(503, "") }
        val second = recorder.captured(ReadCapture.Suggestions) as Recording.ReadStopped

        assertTrue(first.folder != second.folder)
        assertEquals(mapOf("suggestions_first.429.txt" to "429\n"), first.folder.texts())
        assertEquals(mapOf("suggestions_first.503.txt" to "503\n"), second.folder.texts())
    }

    @Test
    fun `a write that stops partway leaves only the earlier atomically saved page`() {
        val session = signedIn()
        val first = pagingBody("https://example.invalid/?offset=17")
        answerPages(first, pagingBody())
        var writes = 0
        val recorder =
            recorder(session, writePrivateFile = { file, text ->
                file.writeText(if (++writes == 2) text.take(10) else text)
                if (writes == 2) throw IOException()
            })

        val stopped = recorder.captured(ReadCapture.Suggestions) as Recording.ReadStopped

        assertEquals(Halt.Storage, stopped.halt)
        assertEquals("suggestions_next", stopped.at)
        assertEquals(1, stopped.answers)
        assertEquals(mapOf("suggestions_first.200.json" to first), stopped.folder.texts())
        assertEquals(
            listOf(stopped.folder),
            stopped.folder
                ?.parentFile
                ?.listFiles()
                ?.toList(),
        )
    }

    @Test
    fun `a staging initialization failure reports no artifact and sends no request`() {
        check(privateReads.mkdirs())
        File(privateReads, "suggestions").writeText("unavailable")

        val stopped = recorder(signedIn()).captured(ReadCapture.Suggestions) as Recording.ReadStopped

        assertEquals(Recording.ReadStopped("files", Halt.Storage, 0, null), stopped)
        assertEquals(0, network.api.requestCount)
    }

    @Test
    fun `manga requires a paging object and contiguous nonempty continuations`() {
        val recorder = recorder(signedIn())
        val invalid =
            listOf(
                pagingBody("not a URL", count = 2),
                pagingBody("https://example.invalid/?offset=1&offset=2", count = 2),
                pagingBody("https://example.invalid/?offset=0", count = 2),
                pagingBody("https://example.invalid/?offset=4", count = 2),
                pagingBody("https://example.invalid/?offset=1", count = 0),
                withPaging(pagingBody(), null),
                withPaging(pagingBody(), JsonNull),
                withPaging(pagingBody(), JsonArray(emptyList())),
            )
        for (body in invalid) {
            answerPages(body)
            val failed = recorder.captured(ReadCapture.MangaList) as Recording.ReadStopped
            assertEquals("manga_list_1", failed.at)
            assertEquals(Halt.Unexpected(200), failed.halt)
            assertEquals(1, failed.answers)
            assertEquals(mapOf("manga_list_1.200.json" to body), failed.folder.texts())
        }
        assertEquals(invalid.size, network.api.requestCount)
        assertTrue(mal.writes.isEmpty())
    }

    @Test
    fun `an invalid JSON value stops the capture and saves only the HTTP status`() {
        answerPages(
            """{"data":[{"value":invalid}],"paging":{"next":"https://example.invalid/?offset=10"}}""",
            pagingBody(),
        )

        val failed = recorder(signedIn()).captured(ReadCapture.Suggestions) as Recording.ReadStopped

        assertEquals("suggestions_first", failed.at)
        assertEquals(Halt.Unexpected(200), failed.halt)
        assertEquals(1, failed.answers)
        assertEquals(mapOf("suggestions_first.200.txt" to "200\n"), failed.folder.texts())
        assertEquals(1, network.api.requestCount)
    }

    @Test
    fun `a capture holds the same sign-in through a token refresh`() {
        val first = pagingBody("https://example.invalid/?offset=10")
        val renewed = "renewed-test-access"
        val second = pagingBody("https://example.invalid/?offset=20&access_token=$renewed")
        fixture.answer = { granted(renewed, "renewed-test-refresh") }
        val responses = ArrayDeque(listOf(answer(200, first), answer(401, ""), answer(200, second)))
        mal.instead = { responses.removeFirst() }

        val end = recorder(signedIn()).captured(ReadCapture.Suggestions) as Recording.ReadRecorded

        assertEquals(2, end.answers)
        assertEquals(second.replace(renewed, "access-token"), end.folder.texts().getValue("suggestions_next.200.json"))
        assertEquals(listOf(ACCESS, ACCESS, renewed), mal.tokens)
        assertEquals(1, fixture.grants.size)
        val requests = List(3) { network.api.received() }
        assertEquals(listOf("0", "10", "10"), requests.map { it.url.queryParameter("offset") })
    }

    @Test
    fun `a replacement sign-in cannot fetch the continuation`() {
        val session = signedIn()
        val recorder = recorder(session) { session.signIn(TokenGrant("replacement-test-access", "replacement-test-refresh")) }
        answerPages(pagingBody("https://example.invalid/?offset=10"))

        val failed = recorder.captured(ReadCapture.Suggestions) as Recording.ReadStopped

        assertEquals("suggestions_next", failed.at)
        assertEquals(Halt.AnotherAccount, failed.halt)
        assertEquals(1, failed.answers)
        assertEquals(1, network.api.requestCount)
        assertTrue(mal.writes.isEmpty())
    }

    @Test
    fun `an action queued before account replacement cannot start under the new sign-in`() {
        val session = signedIn()
        val dispatcher = StandardTestDispatcher()
        val recorder =
            SignedInRecorder(lazyOf(session), network.http, { journal }, { files }, { privateReads }, scope(), dispatcher = dispatcher)
        dispatcher.scheduler.runCurrent()

        val action = recorder.recordReads(ReadCapture.Suggestions)
        assertFalse(action.isCompleted)
        runBlocking { session.signIn(TokenGrant("replacement-test-access", "replacement-test-refresh")) }
        dispatcher.scheduler.runCurrent()

        assertTrue(action.isCompleted)
        assertEquals(Recording.NotStarted(Halt.AnotherAccount), recorder.state.value)
        assertFalse(privateReads.exists())
        assertEquals(0, network.api.requestCount)
    }

    @Test
    fun `a read capture requires an active sign-in before opening storage or requesting anything`() {
        val recorder = recorder(runBlocking { fixture.open() })

        for (capture in ReadCapture.entries) assertEquals(Recording.NotStarted(Halt.NotSignedIn), recorder.captured(capture))

        assertEquals(0, foldersAsked.get())
        assertEquals(0, privateReadsAsked.get())
        assertEquals(0, network.api.requestCount)
    }

    @Test
    fun `same-origin and cross-origin redirects stop both modes before any follow-up`() {
        val recorder = recorder(signedIn())
        val redirects =
            listOf(ReadCapture.Suggestions to "/v2/unexpected", ReadCapture.MangaList to network.signIn.url("/unintended").toString())
        for ((capture, location) in redirects) {
            mal.instead = {
                MockResponse
                    .Builder()
                    .code(307)
                    .setHeader("Location", location)
                    .body(ACCESS)
                    .build()
            }
            val failed = recorder.captured(capture) as Recording.ReadStopped
            assertEquals(Halt.Unexpected(307), failed.halt)
            assertEquals(mapOf("${capture.answer(0)}.307.txt" to "307\n"), failed.folder.texts())
        }
        val requests = List(2) { network.api.received() }
        assertEquals(listOf("/v2/anime/suggestions", "/v2/users/@me/mangalist"), requests.map { it.url.encodedPath })
        assertEquals(2, network.api.requestCount)
        assertEquals(0, network.signIn.requestCount)
    }

    @Test
    fun `a received 503 survives OkHttp attempting a second send`() {
        val first = pagingBody("https://example.invalid/?offset=10")
        mal.instead = { request ->
            if (request.url.queryParameter("offset") == "0") {
                answer(200, first)
            } else {
                MockResponse
                    .Builder()
                    .code(503)
                    .setHeader("Retry-After", "0")
                    .build()
            }
        }
        val failed = recorder(signedIn()).captured(ReadCapture.Suggestions) as Recording.ReadStopped

        assertEquals("suggestions_next", failed.at)
        assertEquals(Halt.Unexpected(503), failed.halt)
        assertEquals(mapOf("suggestions_first.200.json" to first, "suggestions_next.503.txt" to "503\n"), failed.folder.texts())
        assertEquals(2, network.api.requestCount)
    }

    @Test
    fun `a terminal response from a retired sign-in is not published`() {
        val session = signedIn()
        mal.instead = {
            runBlocking { session.signOut() }
            answer(200, pagingBody())
        }

        val failed = recorder(session).captured(ReadCapture.Suggestions) as Recording.ReadStopped

        assertEquals(Halt.AnotherAccount, failed.halt)
        assertEquals(0, failed.answers)
        assertEquals(
            listOf(failed.folder),
            failed.folder
                ?.parentFile
                ?.listFiles()
                ?.toList(),
        )
    }

    @Test
    fun `a received 503 survives a broken response body`() {
        mal.instead = {
            answer(503, "x".repeat(100_000))
                .newBuilder()
                .onResponseBody(SocketEffect.CloseSocket())
                .build()
        }

        val failed = recorder(signedIn()).captured(ReadCapture.MangaList) as Recording.ReadStopped

        assertEquals(Halt.Unexpected(503), failed.halt)
        assertEquals(mapOf("manga_list_1.503.txt" to "503\n"), failed.folder.texts())
        assertEquals(1, network.api.requestCount)
    }

    private fun SignedInRecorder.captured(capture: ReadCapture): Recording =
        runBlocking {
            recordReads(capture).join()
            state.value
        }

    private fun readRequest(
        path: String,
        offset: String,
    ): HttpUrl {
        val request = network.api.received()
        assertEquals("GET", request.method)
        assertEquals(path, request.url.encodedPath)
        assertEquals(offset, request.url.queryParameter("offset"))
        assertEquals("Bearer $ACCESS", request.headers[AUTHORIZATION])
        assertNull(request.headers[CLIENT_ID_HEADER])
        return request.url
    }

    private fun answerPages(vararg bodies: String) {
        val pages = ArrayDeque(bodies.toList())
        mal.instead = { answer(200, pages.removeFirst()) }
    }

    private fun pagingBody(
        next: String? = null,
        count: Int = 10,
    ): String {
        val publicPage = Json.parseToJsonElement(RecordedAnswers.named("list_public_1").body).jsonObject
        val paging = JsonObject(mapOf("next" to (next?.let(::JsonPrimitive) ?: JsonNull)))
        val rows = publicPage.getValue("data").jsonArray.take(count)
        return JsonObject(publicPage + ("paging" to paging) + ("data" to JsonArray(rows))).toString()
    }

    private fun withPaging(
        body: String,
        paging: JsonElement?,
    ): String {
        val page = Json.parseToJsonElement(body).jsonObject
        return JsonObject(if (paging == null) page - "paging" else page + ("paging" to paging)).toString()
    }

    private companion object {
        const val LIFETIME = 2415600L
    }
}
