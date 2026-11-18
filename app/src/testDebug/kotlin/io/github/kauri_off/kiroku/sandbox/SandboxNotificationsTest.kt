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
import io.github.kauri_off.kiroku.data.session.BackgroundRun
import io.github.kauri_off.kiroku.data.session.HeldBack
import io.github.kauri_off.kiroku.notify.EpisodeNotifications
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.scenario.WriteFault
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.KeysInMemory
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

    private fun SandboxGraph.run(trigger: RunTrigger): RunReport = waited { notificationRuns!!.run(trigger) }

    private fun SandboxGraph.coming(): List<Coming> = waited { notifications.observeComing().first { it != null } }!!

    private fun SandboxGraph.sent(): List<SentNotification> = waited { notifications.observeSent().first() }

    // What the device shows, as the engine reads it back.
    private fun shown(): Map<AnimeId, Shown> = waited { EpisodeNotifications(context, { TitleLanguage.Romaji }).shown() }

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

    @Test
    fun `a run by each trigger renews tokens that are due, once, and with MAL's back-off set sends MAL nothing (S7)`() {
        val graph = started()
        graph.signedIn()
        graph.synced()
        graph.run(RunTrigger.AppOpened)
        assertEquals(1, requests(0).count { it == TOKEN })

        for (trigger in RunTrigger.entries) {
            // The tokens are a week old again.
            time.ahead = time.ahead + DUE
            val before = asked()
            assertEquals("$trigger", RunEnd.Done, graph.run(trigger).end)
            assertEquals("$trigger", listOf(TOKEN), requests(before).filter { MAL in it })
            // New tokens are not due.
            val renewed = asked()
            graph.run(trigger)
            assertEquals("$trigger", emptyList<String>(), requests(renewed).filter { MAL in it })
        }

        // MAL refuses a request, and the tokens come due while its back-off lasts.
        time.ahead = time.ahead + DUE
        val watching = waited { graph.list.observeEntries().first() }.first { it.status == ListStatus.Watching }
        graph.scenarios.set(Scenario(writes = WriteFault.Refused))
        assertEquals(Outcome.Failure(DataError.Refused), waited { graph.list.send(ListWrite.MarkNextWatched(watching.anime)) })
        graph.scenarios.set(Scenario())
        assertTrue(File(context.noBackupFilesDir, "sandbox.backoff").isFile)
        val refused = asked()
        for (trigger in RunTrigger.entries) assertEquals("$trigger", RunEnd.Done, graph.run(trigger).end)
        assertTrue(waited { graph.withoutScreen { it.sync() } } is HeldBack.Refused)
        assertEquals(emptyList<String>(), requests(refused).filter { MAL in it })

        // A new process, as the alarm starts one, is held back by the same file.
        val later = started()
        val began = asked()
        assertEquals(RunEnd.Done, later.run(RunTrigger.Alarm).end)
        assertEquals(emptyList<String>(), requests(began).filter { MAL in it })
    }

    @Test
    fun `work without a screen reaches the list through the way in, which renews tokens that are due before it`() {
        val graph = started()
        graph.signedIn()
        time.ahead = DUE
        val before = asked()

        assertEquals(BackgroundRun.Ran(Outcome.Success(Unit)), waited { graph.withoutScreen { it.sync() } })

        val sent = requests(before).filter { MAL in it }
        assertEquals(TOKEN, sent.first())
        assertTrue("$sent", sent.drop(1).isNotEmpty() && sent.drop(1).all { it == LIST })
    }

    @Test
    fun `after a sign-out the database holds no record, nothing is posted and no alarm is set (S9)`() {
        val graph = started()
        graph.announced()
        assertTrue(soon in shown())
        assertTrue(rows("notification_title") > 0)
        assertTrue(rows("notification_sent") > 0)
        assertTrue(alarm() != null)

        waited { graph.session.signOut() }

        assertNoRecord()
        assertEquals(emptyList<SentNotification>(), graph.sent())
        assertEquals(emptyList<Coming>(), graph.coming())
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

    // A device with a connection, which a test's has not: the alarm's run asks the airing source only with one.
    private object Online : Device {
        override fun isOnline() = true

        override fun observeOnline(): Flow<Boolean> = flowOf(true)

        override fun isMainThread() = Looper.getMainLooper().thread === Thread.currentThread()
    }

    private companion object {
        const val WAIT = 20_000L
        const val MAL = "sandbox.invalid"
        const val TOKEN = "POST sandbox.invalid/v1/oauth2/token"
        const val LIST = "GET sandbox.invalid/v2/users/@me/animelist"

        // The age at which tokens are renewed, and a little more.
        val DUE: Duration = Duration.ofDays(7).plusHours(1)
    }
}
