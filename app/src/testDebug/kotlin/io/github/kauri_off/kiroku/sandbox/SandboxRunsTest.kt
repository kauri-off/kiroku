package io.github.kauri_off.kiroku.sandbox

import android.app.AlarmManager
import android.content.Intent
import android.os.Looper
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring
import io.github.kauri_off.kiroku.notify.EpisodeNotifications
import io.github.kauri_off.kiroku.notify.RunReceiver
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

    private fun shown() = waited { EpisodeNotifications(context, { TitleLanguage.Romaji }).shown() }

    @Test
    fun `the alarm the engine set starts the run that posts the episode, and each moment the app is started at sets the alarm again`() {
        waited {
            graph.signIn.start { error("The sandbox opened a browser") }
            assertEquals(SignInStep.SignedIn, graph.signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed })
            assertEquals(Outcome.Success(Unit), graph.list.sync())
            assertEquals(Outcome.Success(Unit), graph.airing.refresh())
            graph.notificationRuns!!.run(RunTrigger.AppOpened)
        }
        val aired = waited { graph.notifications.observeComing().first { it != null } }!!.single { it.anime.id == soon }
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

    private companion object {
        val WAIT = 20.seconds
        const val PAUSE = 20L
    }
}
