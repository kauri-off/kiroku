package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.network.NetworkFixture
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
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
    private val foldersAsked = AtomicInteger()
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
        pause: suspend () -> Unit = { events += "pause" },
    ): SignedInRecorder =
        SignedInRecorder(lazyOf(session), network.http, {
            journal
        }, { files.also { foldersAsked.incrementAndGet() } }, scope, pause).also { runBlocking { it.started.join() } }

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

    private fun File.texts(): Map<String, String> = listFiles().orEmpty().associate { it.name to it.readText() }

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
        val end = recorder(session).recorded() as Recording.Stopped

        assertEquals(listOf("journal", Halt.Storage, ListAfter.Untouched), listOf(end.at, end.halt, end.list))
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

    private companion object {
        const val LIFETIME = 2415600L
    }
}
