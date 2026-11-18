package io.github.kauri_off.kiroku.sandbox

import android.app.AlarmManager
import android.app.NotificationManager
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.Coming
import io.github.kauri_off.kiroku.core.notify.Recheck
import io.github.kauri_off.kiroku.core.notify.RunEnd
import io.github.kauri_off.kiroku.core.notify.RunReport
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.notify.SentNotification
import io.github.kauri_off.kiroku.core.notify.Shown
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring
import io.github.kauri_off.kiroku.data.sandbox.SandboxMal
import io.github.kauri_off.kiroku.notificationlog.LoggedPost
import io.github.kauri_off.kiroku.notify.EpisodeNotifications
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.scenario.WriteFault
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.KeysInMemory
import io.github.kauri_off.kiroku.wiring.ListWork
import io.github.kauri_off.kiroku.wiring.LiveParts
import io.github.kauri_off.kiroku.wiring.applicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

// Notifications on the sandbox: the engine over the source's database, the device's notifications and the system's alarm,
// in one process of the app after another over the same files, with no request leaving the device.
@RunWith(RobolectricTestRunner::class)
class SandboxNotificationsTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    @get:Rule(order = 0)
    val keystore =
        object : ExternalResource() {
            override fun before() = KeysInMemory.install()

            override fun after() = KeysInMemory.uninstall()
        }

    @get:Rule(order = 1)
    val connections = SandboxAppTest.NoConnection()

    @get:Rule(order = 2)
    val hurried = HurriedAniList()

    @get:Rule(order = 2)
    val time = MovedClock()

    private val context get() = RuntimeEnvironment.getApplication()
    private val unexpected = CopyOnWriteArrayList<Throwable>()

    private class Process(
        val graph: SandboxGraph,
        val scope: CoroutineScope,
    )

    private var process: Process? = null

    private fun <T> waited(block: suspend () -> T): T = runBlocking { withTimeout(WAIT) { block() } }

    // A new process of the app on the sandbox, over the files the one before it left; that one has ended.
    private fun started(): SandboxGraph {
        ended()
        val scope = applicationScope(unexpected::add)
        val graph =
            SandboxGraph.create(context, Clock.systemUTC(), Online) { source, device, clock ->
                LiveParts(context, source, SandboxMal.CLIENT_ID, clock, scope, unexpected::add, device = device)
            }
        process = Process(graph, scope)
        return graph
    }

    private fun ended() {
        val was = process ?: return
        process = null
        waited {
            was.scope.coroutineContext.job
                .cancelAndJoin()
        }
    }

    @After
    fun end() {
        ended()
        assertEquals(emptyList<String>(), unexpected.map { it.stackTraceToString() })
        assertEquals(emptyList<URI>(), connections.asked)
    }

    private fun now(): Instant = Instant.now().plus(time.ahead)

    private fun SandboxGraph.signedIn() =
        waited {
            signIn.start { error("The sandbox opened a browser") }
            assertEquals(SignInStep.SignedIn, signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed })
        }

    // The list downloaded and the airing data of its tracked titles stored.
    private fun SandboxGraph.synced() {
        assertEquals(Outcome.Success(Unit), waited { list.sync() })
        assertEquals(Outcome.Success(Unit), waited { airing.refresh() })
    }

    private fun SandboxGraph.run(trigger: RunTrigger): RunReport = waited { withoutScreen.notificationRuns!!.run(trigger) }

    private fun SandboxGraph.coming(): List<Coming> = waited { notifications.observeComing().first { it != null } }!!

    private fun SandboxGraph.sent(): List<SentNotification> = waited { notifications.observeSent().first() }

    // What the device shows, as the engine reads it back.
    private fun shown(): Map<AnimeId, Shown> = waited { EpisodeNotifications(context, SOURCE, { TitleLanguage.Romaji }).shown() }

    private fun posted() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    // The system's one alarm of the app; none when it has none.
    private fun alarm(): Instant? =
        shadowOf(context.getSystemService(AlarmManager::class.java))
            .scheduledAlarms
            .also { assertTrue("${it.size} alarms", it.size <= 1) }
            .singleOrNull()
            ?.let { Instant.ofEpochMilli(it.triggerAtMs) }

    private fun rows(
        table: String,
        where: String = "1",
    ): Long =
        SQLiteDatabase.openDatabase(context.getDatabasePath("sandbox.db").path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use {
                it.moveToFirst()
                it.getLong(0)
            }
        }

    private fun requests(since: Int): List<String> =
        process!!
            .graph.sandbox.mal.asked
            .drop(since)

    private fun asked(): Int =
        process!!
            .graph.sandbox.mal.asked.size

    private val soon = AnimeId(SandboxAiring.SOON)

    // The clock passes the air time of the title that airs soon after a reset; the episode that airs then.
    private fun SandboxGraph.soonAired(): Coming {
        val next = coming().single { it.anime.id == soon }
        assertTrue("$next", Duration.between(now(), next.airsAt) <= SandboxAiring.SOON_AFTER)
        assertEquals(next.airsAt, next.notifyAt)
        time.ahead = time.ahead + Duration.between(now(), next.notifyAt).plusSeconds(1)
        return next
    }

    // The episodes of the soon title among what a run posted or the app lists as sent; another title may air in the same minutes.
    private fun List<SentNotification>.ofSoon(): List<Int> = filter { it.anime.id == soon }.map { it.episode }

    // Signed in to a sandbox laid out as of now, synced and opened once, with the soon title's episode aired since and
    // announced by the alarm's run.
    private fun SandboxGraph.announced(): Coming {
        signedIn()
        waited { withContext(Dispatchers.IO) { sandbox.reset() } }
        synced()
        assertEquals(emptyList<SentNotification>(), run(RunTrigger.AppOpened).posted)
        val aired = soonAired()
        assertEquals(listOf(aired.episode), run(RunTrigger.Alarm).posted.ofSoon())
        return aired
    }

    @Test
    fun `when the clock passes the air time, the alarm's run posts one notification, and no later process posts it again (N1, N10)`() {
        val first = started()
        first.signedIn()
        first.synced()
        val opened = first.run(RunTrigger.AppOpened)
        assertEquals(RunEnd.Done, opened.end)
        assertEquals(emptyList<SentNotification>(), opened.posted)
        assertEquals(emptyMap<AnimeId, Shown>(), shown())
        assertEquals(first.coming().minOf { it.notifyAt }, alarm())
        val aired = first.soonAired()

        val report = first.run(RunTrigger.Alarm)

        assertEquals(RunEnd.Done, report.end)
        assertEquals(Recheck.Answered, report.recheck)
        assertEquals(listOf(aired.episode), report.posted.ofSoon())
        assertEquals(report.posted.map { it.anime.id }.toSet(), shown().keys)
        assertEquals(aired.episode, shown().getValue(soon).episode)
        val next = first.coming().minOf { it.notifyAt }
        assertTrue(next.isAfter(now()))
        assertEquals(next, alarm())
        assertEquals(1L, rows("notification_sent", "title_id = ${soon.value}"))
        val notifications = posted()

        // A new process over the same files, started by each of the moments that start one.
        for (trigger in listOf(RunTrigger.DeviceRestarted, RunTrigger.AppUpdated, RunTrigger.Alarm, RunTrigger.AppOpened)) {
            val later = started()
            assertEquals("$trigger", emptyList<SentNotification>(), later.run(trigger).posted)
            assertEquals(notifications.size, posted().size)
            for ((before, after) in notifications.zip(posted())) assertSame("$trigger", before, after)
            assertEquals(listOf(aired.episode), later.sent().ofSoon())
            assertEquals(next, alarm())
        }
        assertEquals(1L, rows("notification_sent", "title_id = ${soon.value}"))
        // The record is the sandbox's own: nothing of the real account's was made.
        val folders = listOf(context.noBackupFilesDir, context.getDatabasePath("sandbox.db").parentFile!!)
        assertEquals(emptyList<String>(), folders.flatMap { it.list().orEmpty().toList() }.filter { it.startsWith("account.") })
    }

    @Test
    fun `a build with notifications started over the files of one without them announces nothing that aired before (N5)`() {
        // The earlier build: signed in and synced, with nothing of notifications ever asked for.
        val earlier = started()
        earlier.signedIn()
        earlier.synced()
        val schedule = waited { earlier.airing.observeSchedules().first() }.getValue(soon)
        val aired = schedule.episodes.first { it.airsAt.isAfter(now()) }
        assertEquals(0L, rows("notification_state"))
        time.ahead = Duration.between(now(), aired.airsAt).plusSeconds(1)

        val updated = started()
        for (trigger in listOf(RunTrigger.AppUpdated, RunTrigger.AppOpened, RunTrigger.Alarm)) {
            val report = updated.run(trigger)
            assertEquals("$trigger", RunEnd.Done, report.end)
            assertEquals("$trigger", emptyList<SentNotification>(), report.posted)
        }
        assertEquals(emptyMap<AnimeId, Shown>(), shown())
        assertEquals(emptyList<SentNotification>(), updated.sent())

        // What airs after it is announced.
        val next = updated.coming().single { it.anime.id == soon }
        assertEquals(aired.number + 1, next.episode)
        assertEquals(updated.coming().minOf { it.notifyAt }, alarm())
        time.ahead = time.ahead + Duration.between(now(), next.notifyAt).plusSeconds(1)
        assertEquals(listOf(next.episode), updated.run(RunTrigger.Alarm).posted.ofSoon())
    }

    // MAL refuses a write, which sets its back-off; the title written to.
    private fun SandboxGraph.refused(): AnimeId {
        val watching = waited { list.observeEntries().first() }.first { it.status == ListStatus.Watching }
        scenarios.set(Scenario(writes = WriteFault.Refused))
        assertEquals(Outcome.Failure(DataError.Refused), waited { list.send(ListWrite.MarkNextWatched(watching.anime)) })
        scenarios.set(Scenario())
        assertTrue(File(context.noBackupFilesDir, "sandbox.backoff").isFile)
        return watching.anime.id
    }

    @Test
    fun `only the opening's run renews tokens that are due, once, and with MAL's back-off set no run sends MAL anything (S7)`() {
        val graph = started()
        graph.signedIn()
        graph.synced()
        graph.run(RunTrigger.AppOpened)
        assertEquals(1, requests(0).count { it == TOKEN })

        // A run the system starts is let go by its hold, and MAL revokes the old tokens as it answers: none renews.
        time.ahead = time.ahead + DUE
        val due = asked()
        for (trigger in RunTrigger.entries - RunTrigger.AppOpened) assertEquals("$trigger", RunEnd.Done, graph.run(trigger).end)
        assertEquals(emptyList<String>(), requests(due).filter { MAL in it })
        // Nor does a new process such a run starts, by its run or by reading its session.
        val later = started()
        val began = asked()
        assertEquals(RunEnd.Done, later.run(RunTrigger.Alarm).end)
        assertTrue(waited { later.session.observeSession().first() } is Session.SignedIn)
        assertEquals(RunEnd.Done, later.run(RunTrigger.DeviceRestarted).end)
        assertEquals(emptyList<String>(), requests(began).filter { MAL in it })

        assertEquals(RunEnd.Done, later.run(RunTrigger.AppOpened).end)
        assertEquals(listOf(TOKEN), requests(began).filter { MAL in it })
        // New tokens are not due.
        later.run(RunTrigger.AppOpened)
        assertEquals(listOf(TOKEN), requests(began).filter { MAL in it })

        // MAL refuses a request, and the tokens come due while its back-off lasts.
        time.ahead = time.ahead + DUE
        later.refused()
        val refused = asked()
        for (trigger in RunTrigger.entries) assertEquals("$trigger", RunEnd.Done, later.run(trigger).end)
        assertTrue(waited { later.withoutScreen.syncList() } is ListWork.Refused)
        assertEquals(emptyList<String>(), requests(refused).filter { MAL in it })
    }

    // W5 owes a download for a write that died with its process, and the list's engine starts it at its first read.
    @Test
    fun `with MAL's back-off set and a write that died with its process, a process the alarm starts sends MAL nothing`() {
        val first = started()
        first.signedIn()
        first.synced()
        first.run(RunTrigger.AppOpened)
        val written = first.refused()
        ended()
        // The entry as a write leaves it whose request was not answered when the process died.
        SQLiteDatabase.openDatabase(context.getDatabasePath("sandbox.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE list_entry SET unconfirmed = 1 WHERE title_id = ${written.value}")
        }
        assertEquals(1L, rows("list_entry", "unconfirmed = 1"))

        val later = started()
        val began = asked()
        for (trigger in listOf(RunTrigger.Alarm, RunTrigger.DeviceRestarted, RunTrigger.AppUpdated)) {
            assertEquals("$trigger", RunEnd.Done, later.run(trigger).end)
        }
        // The engine has read the list, which returned the entry to what MAL confirmed; its download waits for the back-off's end.
        assertEquals(0L, rows("list_entry", "unconfirmed = 1"))
        Thread.sleep(OWED)
        assertEquals(emptyList<String>(), requests(began).filter { MAL in it })
        // It stays owed: the list has no time of a download, so the next screen that shows it downloads it.
        assertEquals(null, waited { later.list.observeSyncedAt().first() })
        assertEquals(Outcome.Success(Unit), waited { later.list.sync() })
        assertTrue(waited { later.list.observeSyncedAt().first() } != null)
    }

    @Test
    fun `work without a screen reaches the list through MAL's way in, which renews tokens that are due before it`() {
        val graph = started()
        graph.signedIn()
        time.ahead = DUE
        val before = asked()

        assertEquals(ListWork.Done(Outcome.Success(Unit)), waited { graph.withoutScreen.syncList() })

        val sent = requests(before).filter { MAL in it }
        assertEquals(TOKEN, sent.first())
        assertTrue("$sent", sent.drop(1).isNotEmpty() && sent.drop(1).all { it == LIST })
        // A write goes the same way, and is held back with everything else once MAL refuses.
        val watching = waited { graph.list.observeEntries().first() }.first { it.status == ListStatus.Watching }
        val write = ListWrite.MarkNextWatched(watching.anime)
        assertEquals(ListWork.Done(Outcome.Success(Unit)), waited { graph.withoutScreen.sendToList(write) })
        graph.refused()
        val refused = asked()
        assertTrue(waited { graph.withoutScreen.sendToList(write) } is ListWork.Refused)
        assertEquals(emptyList<String>(), requests(refused).filter { MAL in it })
    }

    // A debug or preview build's sources share the device: each has its own notifications there, as it has its own files.
    @Test
    fun `a run neither removes nor takes for its own a notification another source posted for the same title`() {
        val graph = started()
        val aired = graph.announced()
        val own = shown().getValue(soon)
        val other = EpisodeNotifications(context, "account", { TitleLanguage.Romaji })
        val others = own.copy(episode = aired.episode + 7)
        waited { other.post(graph.coming().first { it.anime.id == soon }.anime, others, alert = true) }
        val sent = graph.sent()

        for (trigger in listOf(RunTrigger.AppOpened, RunTrigger.Alarm)) graph.run(trigger)

        assertEquals(mapOf(soon to others), waited { other.shown() })
        assertEquals(own, shown().getValue(soon))
        assertEquals(sent, graph.sent())
        // A sign-out of this source leaves the other's as well.
        waited { graph.session.signOut() }
        assertEquals(emptyMap<AnimeId, Shown>(), shown())
        assertEquals(mapOf(soon to others), waited { other.shown() })
        waited { other.withdraw(setOf(soon)) }
    }

    @Test
    fun `after a sign-out the database holds no record, nothing is posted and no alarm is set (S9)`() {
        // In the process that announced, and in a later one in which nothing has asked for notifications.
        for (later in listOf(false, true)) {
            var graph = started()
            graph.announced()
            assertTrue(soon in shown())
            assertTrue(rows("notification_title") > 0)
            assertTrue(rows("notification_sent") > 0)
            assertTrue(alarm() != null)
            if (later) graph = started()

            waited { graph.session.signOut() }

            assertNoRecord()
            assertEquals(emptyList<SentNotification>(), graph.sent())
            assertEquals(emptyList<Coming>(), graph.coming())
        }
    }

    private fun assertNoRecord() {
        assertEquals(0L, rows("notification_title"))
        assertEquals(0L, rows("notification_sent"))
        assertEquals(0L, rows("notification_state", "owner IS NOT NULL OR since IS NOT NULL OR planned_since IS NOT NULL"))
        assertEquals(emptyMap<AnimeId, Shown>(), shown())
        assertEquals(0, posted().size)
        assertEquals(null, alarm())
    }

    // The process died after the tokens were removed and before anything was forgotten.
    private fun signedOutUnfinished() {
        val graph = started()
        graph.announced()
        ended()
        File(context.noBackupFilesDir, "sandbox.tokens").writeBytes(ByteArray(0))
        assertTrue(rows("notification_sent") > 0)
        assertTrue(soon in shown())
    }

    @Test
    fun `a sign-out its process did not finish is finished by the first run of the next process, whatever starts it (S9)`() {
        for (trigger in listOf(RunTrigger.Alarm, RunTrigger.DeviceRestarted, RunTrigger.AppOpened)) {
            signedOutUnfinished()
            val next = started()
            assertEquals(Session.SignedOut, waited { next.session.observeSession().first() })

            assertEquals("$trigger", RunEnd.SignedOut, next.run(trigger).end)

            assertNoRecord()
        }
    }

    @Test
    fun `after a sign-out its process did not finish, another sign-in is shown nothing of what was sent before it (S9)`() {
        signedOutUnfinished()
        val next = started()
        next.signedIn()
        next.synced()

        // The screen's read is the first thing asked of notifications in this process.
        assertEquals(emptyList<SentNotification>(), next.sent())

        assertEquals(0L, rows("notification_sent"))
        assertEquals(emptyMap<AnimeId, Shown>(), shown())
    }

    // What the app holds of the soon title's next notification, once it holds [at].
    private fun SandboxGraph.comingAt(at: Instant): Coming =
        waited { notifications.observeComing().first { coming -> coming?.any { it.anime.id == soon && it.notifyAt == at } == true } }!!
            .single { it.anime.id == soon }

    @Test
    fun `a planted episode is the app's at its time after a new process and a refresh, and once postponed is announced only then (N3)`() {
        val first = started()
        first.signedIn()
        waited { withContext(Dispatchers.IO) { first.sandbox.reset() } }
        first.synced()
        first.run(RunTrigger.AppOpened)

        val planted = checkNotNull(waited { first.plant(Duration.ofMinutes(20)) })
        val coming = first.comingAt(planted.at)
        assertEquals(planted.episode, coming.episode)
        assertTrue(Duration.between(now(), planted.at) in Duration.ofMinutes(19)..Duration.ofMinutes(20))

        // A new process, and every refresh it makes, brings it back unchanged.
        val later = started()
        assertEquals(listOf(planted), later.sandbox.planted)
        later.synced()
        later.run(RunTrigger.AppOpened)
        assertEquals(coming, later.comingAt(planted.at))

        // Postponed at AniList, which the app learns only by asking.
        val postponed = checkNotNull(waited { later.postpone(Duration.ofMinutes(15)) })
        assertEquals(planted.at.plus(Duration.ofMinutes(15)), postponed.at)
        assertEquals(coming, later.comingAt(planted.at))
        time.ahead = time.ahead + Duration.between(now(), planted.at).plusSeconds(1)
        val held = later.run(RunTrigger.Alarm)
        assertEquals(Recheck.Answered, held.recheck)
        assertEquals(emptyList<Int>(), held.posted.ofSoon())
        assertEquals(planted.episode, later.comingAt(postponed.at).episode)

        time.ahead = time.ahead + Duration.between(now(), postponed.at).plusSeconds(1)
        val announced = later.run(RunTrigger.Alarm)
        assertEquals(listOf(planted.episode), announced.posted.ofSoon())

        // The log of the runs, with each delay, is there in the next process too.
        val logged =
            started()
                .runLog
                .also { it.stored() }
                .runs.value
        val alarms = logged.filter { it.trigger == RunTrigger.Alarm }.take(2)
        assertEquals(listOf(announced.began, held.began), alarms.map { it.began })
        val post = alarms.first().posted.single { it.anime == soon }
        assertEquals(postponed.at, post.notifyAt)
        assertTrue("${post.delay}", post.delay >= Duration.ofSeconds(1) && post.delay < Duration.ofSeconds(WAIT / 1000))
        assertEquals(Recheck.Answered, alarms.last().recheck)
        assertEquals(emptyList<LoggedPost>(), alarms.last().posted.filter { it.anime == soon })
    }

    // A device with a connection, which a test's has not: the alarm's run asks the airing source only with one.
    private object Online : Device {
        override fun isOnline() = true

        override fun observeOnline(): Flow<Boolean> = flowOf(true)

        override fun isMainThread() = Looper.getMainLooper().thread === Thread.currentThread()
    }

    private companion object {
        const val WAIT = 20_000L
        const val SOURCE = "sandbox"
        const val MAL = "sandbox.invalid"

        // Longer than the list's engine takes to start a download it owes.
        const val OWED = 2_000L
        const val TOKEN = "POST sandbox.invalid/v1/oauth2/token"
        const val LIST = "GET sandbox.invalid/v2/users/@me/animelist"

        // The age at which tokens are renewed, and a little more.
        val DUE: Duration = Duration.ofDays(7).plusHours(1)
    }
}
