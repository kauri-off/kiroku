package io.github.kauri_off.kiroku.sandbox

import android.app.AlarmManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.work.NetworkType
import androidx.work.WorkInfo
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.Coming
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring
import io.github.kauri_off.kiroku.notify.EpisodeNotifications
import io.github.kauri_off.kiroku.notify.PeriodicWork
import io.github.kauri_off.kiroku.notify.RunReceiver
import io.github.kauri_off.kiroku.notify.TestScheduledWork
import io.github.kauri_off.kiroku.notify.heldRuns
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.KeysInMemory
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// The app itself on the sandbox: the system's alarm and each moment the app is started at reach the live engine, through
// the receiver the manifest declares and the application's own runs.
@RunWith(RobolectricTestRunner::class)
class SandboxRunsTest {
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
    val source = StartsOn(DataSource.Sandbox)

    @get:Rule(order = 2)
    val hurried = HurriedAniList()

    @get:Rule(order = 2)
    val time = MovedClock()

    private val context get() = RuntimeEnvironment.getApplication()
    private val graph get() = context.appGraph as SandboxGraph
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val soon = AnimeId(SandboxAiring.SOON)

    private fun <T> waited(block: suspend () -> T): T = runBlocking { withTimeout(WAIT.inWholeMilliseconds) { block() } }

    // Met while the main thread runs what is posted to it, as a broadcast's delivery is.
    private fun until(
        what: String,
        done: () -> Boolean,
    ) {
        val limit = TimeSource.Monotonic.markNow() + WAIT
        while (!done()) {
            assertTrue(what, limit.hasNotPassedNow())
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(PAUSE)
        }
    }

    private fun alarm(): Instant? =
        shadowOf(alarms).scheduledAlarms.also { assertTrue("${it.size} alarms", it.size <= 1) }.singleOrNull()?.let {
            Instant.ofEpochMilli(it.triggerAtMs)
        }

    private fun shown() = waited { EpisodeNotifications(context, "sandbox", { TitleLanguage.Romaji }).shown() }

    // Signed in, synced and opened once; the soon title's episode that comes next.
    private fun opened(): Coming {
        waited {
            graph.signIn.start { error("The sandbox opened a browser") }
            assertEquals(SignInStep.SignedIn, graph.signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed })
            assertEquals(Outcome.Success(Unit), graph.list.sync())
            assertEquals(Outcome.Success(Unit), graph.airing.refresh())
            graph.withoutScreen.notificationRuns!!.run(RunTrigger.AppOpened)
        }
        return waited { graph.notifications.observeComing().first { it != null } }!!.single { it.anime.id == soon }
    }

    @Test
    fun `the alarm the engine set starts the run that posts the episode, and each moment the app is started at sets the alarm again`() {
        // Each moment schedules the periodic work too.
        TestScheduledWork.install(context)
        val aired = opened()
        time.ahead = Duration.between(Instant.now(), aired.notifyAt).plusSeconds(1)

        // The system's alarm fires: its broadcast, as the system holds it, is sent.
        shadowOf(alarms)
            .scheduledAlarms
            .single()
            .operation!!
            .send()
        until("the episode is posted") { shown()[soon]?.episode == aired.episode }
        // The run sets a near alarm first; its last step leaves the alarm at what comes next.
        val coming = { waited { graph.notifications.observeComing().first() }!!.minOf { it.notifyAt } }
        until("the alarm is at what comes next") { coming().isAfter(Instant.now().plus(time.ahead)) && alarm() == coming() }
        val next = alarm()

        // N8: a restart, an update and the exact-alarm access each lose or change the alarm, and each sets it again.
        for (action in listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )) {
            alarms.cancel(shadowOf(alarms).scheduledAlarms.single().operation!!)
            assertEquals(null, alarm())
            // To the app's receiver alone: scheduled work's own receivers would set that work up for every later test.
            context.sendBroadcast(Intent(context, RunReceiver::class.java).setAction(action))
            until(action) { alarm() == next }
        }

        // The app's opening does too, as after a force-stop: what the application starts each time it comes to the foreground.
        alarms.cancel(shadowOf(alarms).scheduledAlarms.single().operation!!)
        checkNotNull(context.heldRuns).start(RunTrigger.AppOpened)
        until("the opening") { alarm() == next }
        assertEquals(setOf(soon), shown().keys.filter { it == soon }.toSet())
        assertEquals(emptyList<URI>(), connections.asked)
    }

    private fun works(): Map<String, WorkInfo> =
        listOf(PeriodicWork.ONLINE, PeriodicWork.STORED).associateWith { TestScheduledWork.periodic(context, it).single() }

    // The work under [name] run as the system runs it at its first time, and ended.
    private fun ran(name: String) {
        val work = TestScheduledWork.periodic(context, name).single()
        TestScheduledWork.run(context, work)
        until("the run of $name has ended") {
            TestScheduledWork.periodic(context, name).single().let {
                it.state == WorkInfo.State.ENQUEUED && it.nextScheduleTimeMillis > work.nextScheduleTimeMillis
            }
        }
    }

    @Test
    fun `the two periodic works are each there once however often they are scheduled, the first run an interval away`() {
        TestScheduledWork.install(context)
        val aired = opened()
        time.ahead = Duration.between(Instant.now(), aired.notifyAt).plusSeconds(1)
        val asked = graph.sandbox.mal.asked.size

        PeriodicWork.schedule(context)
        val first = works()
        Thread.sleep(PAUSE)
        repeat(2) { PeriodicWork.schedule(context) }
        val again = works()

        for ((name, work) in again) {
            assertEquals(name, first.getValue(name).id, work.id)
            assertEquals(name, first.getValue(name).nextScheduleTimeMillis, work.nextScheduleTimeMillis)
            assertEquals(name, PeriodicWork.INTERVAL.toMillis(), work.periodicityInfo!!.repeatIntervalMillis)
            assertEquals(name, PeriodicWork.INTERVAL.toMillis(), work.initialDelayMillis)
            assertEquals(name, WorkInfo.State.ENQUEUED, work.state)
            assertEquals(name, 0, work.runAttemptCount)
        }
        // S7: only a job that requires a network is given one in the rare and restricted buckets.
        assertEquals(NetworkType.CONNECTED, again.getValue(PeriodicWork.ONLINE).constraints.requiredNetworkType)
        assertEquals(NetworkType.NOT_REQUIRED, again.getValue(PeriodicWork.STORED).constraints.requiredNetworkType)
        // Nothing ran when they were scheduled, though the episode is due.
        assertEquals(null, shown()[soon])
        assertEquals(
            emptyList<String>(),
            graph.sandbox.mal.asked
                .drop(asked),
        )
    }

    @Test
    fun `run by the system, the work that needs no network posts what is overdue from stored data alone, and the other downloads`() {
        TestScheduledWork.install(context)
        val aired = opened()
        time.ahead = Duration.between(Instant.now(), aired.notifyAt).plusSeconds(1)
        PeriodicWork.schedule(context)
        val asked = graph.sandbox.mal.asked.size

        ran(PeriodicWork.STORED)

        assertEquals(aired.episode, shown()[soon]?.episode)
        assertEquals(
            emptyList<String>(),
            graph.sandbox.mal.asked
                .drop(asked),
        )

        // The test's device has a connection now, as the system gives the work that requires one.
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val internet =
            ShadowNetworkCapabilities.newInstance().also {
                shadowOf(
                    it,
                ).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, internet)
        ran(PeriodicWork.ONLINE)

        assertTrue(
            "${graph.sandbox.mal.asked.drop(asked)}",
            graph.sandbox.mal.asked
                .drop(asked)
                .any { it.endsWith(LIST) },
        )
        assertEquals(emptyList<URI>(), connections.asked)
    }

    private companion object {
        val WAIT = 20.seconds
        const val PAUSE = 20L
        const val LIST = "/v2/users/@me/animelist"
    }
}
