package io.github.kauri_off.kiroku.sandbox

import android.os.Looper
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.sandbox.SandboxMal
import io.github.kauri_off.kiroku.data.sandbox.SandboxTitles
import io.github.kauri_off.kiroku.scenario.AniListFault
import io.github.kauri_off.kiroku.scenario.ResetState
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.DeviceTime
import io.github.kauri_off.kiroku.wiring.KeysInMemory
import io.github.kauri_off.kiroku.wiring.LiveParts
import io.github.kauri_off.kiroku.wiring.applicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.URI
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

// The sandbox as the device's network has it, and its reset as a new start of AniList's client.
@RunWith(RobolectricTestRunner::class)
class SandboxNetworkTest {
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

    // The app's own graph is the demo's, which opens none of the sandbox's files.
    @get:Rule(order = 2)
    val source = StartsOn(DataSource.Demo)

    private val context get() = RuntimeEnvironment.getApplication()
    private val unexpected = CopyOnWriteArrayList<Throwable>()

    private val time = SteppedTime()
    private val device = SwitchedDevice()
    private lateinit var scope: CoroutineScope
    private lateinit var graph: SandboxGraph

    @Before
    fun start() {
        SandboxGraph.aniListTime = time
        scope = applicationScope(unexpected::add)
        graph =
            SandboxGraph.create(context, Clock.systemUTC(), device) { source, device, clock ->
                LiveParts(context, source, SandboxMal.CLIENT_ID, clock, scope, unexpected::add, device = device)
            }
    }

    @After
    fun end() {
        waited { scope.coroutineContext.job.cancelAndJoin() }
        SandboxGraph.aniListTime = DeviceTime
        assertEquals(emptyList<String>(), unexpected.map { it.stackTraceToString() })
        assertEquals(emptyList<URI>(), connections.asked)
    }

    private fun <T> waited(block: suspend () -> T): T = runBlocking { withTimeout(WAIT) { block() } }

    private fun until(done: () -> Boolean) =
        waited {
            while (!done()) delay(POLL)
        }

    // The next step is past the wait AniList's client keeps after a failure.
    private fun later() {
        time.passed.addAndGet(1.minutes.inWholeNanoseconds)
    }

    private fun signedInAndSynced() {
        waited {
            graph.signIn.start { error("The sandbox opened a browser") }
            assertEquals(SignInStep.SignedIn, graph.signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed })
        }
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        assertEquals(Outcome.Success(Unit), waited { graph.airing.refresh() })
        later()
    }

    private fun aniListAsked(
        id: Long,
        since: Int,
    ): Boolean =
        graph.sandbox.aniList.asked
            .drop(since)
            .any { id in it }

    @Test
    fun `with the device offline and the panel's Offline off, MAL and AniList fail with no connection, and answer once it is back`() {
        signedInAndSynced()
        val title =
            waited { graph.list.observeEntries().first() }
                .first { it.status == ListStatus.Watching }
                .anime.id

        device.online.value = false
        val since = graph.sandbox.mal.asked.size
        assertEquals(Outcome.Failure(DataError.NoConnection), waited { graph.list.sync() })
        assertEquals(Outcome.Failure(DataError.NoConnection), waited { graph.anime.refresh(title) })
        assertEquals(Outcome.Failure(DataError.NoConnection), waited { graph.airing.refresh() })
        val asked =
            graph.sandbox.mal.asked
                .drop(since)
        assertTrue("$asked", asked.any { it.startsWith("GET sandbox.invalid/v2/users/@me/animelist") })
        assertTrue("$asked", "POST graphql.anilist.co/" in asked)
        assertEquals(Scenario(), graph.scenarios.scenario.value)

        device.online.value = true
        later()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        assertEquals(Outcome.Success(Unit), waited { graph.anime.refresh(title) })
        assertEquals(Outcome.Success(Unit), waited { graph.airing.refresh() })
    }

    // A title yet to air off the sandbox's starting list, so that the reset's own pull does not ask for it again.
    private fun notOnTheList(): AnimeId {
        val listed = waited { graph.list.observeEntries().first() }.map { it.anime.id.value }.toSet()
        val coming =
            SandboxTitles.Answers.flatMap { name ->
                val answer = JSONObject(context.assets.open("recorded/$name.200.json").use { it.readBytes().decodeToString() })
                val data = answer.optJSONArray("data")
                if (data == null) listOf(answer) else List(data.length()) { data.getJSONObject(it).getJSONObject("node") }
            }
        return AnimeId(coming.first { it.optString("status") == "not_yet_aired" && it.getLong("id") !in listed }.getLong("id"))
    }

    // A title tracked while AniList fails is held in the client's cool-down; a reset ends it, as a new process would.
    @Test
    fun `after the sandbox's reset a title whose fetch failed before it is asked of AniList again when it is tracked again`() {
        signedInAndSynced()
        val id = notOnTheList()
        assertEquals(Outcome.Success(Unit), waited { graph.anime.refresh(id) })
        val anime = checkNotNull(waited { graph.anime.observeAnime(id).first { it != null } })
        val planned = ListValues(ListStatus.Planned, 0, null, null, null)

        graph.scenarios.set(Scenario(aniList = AniListFault.Fails))
        val failed = graph.sandbox.aniList.asked.size
        waited { graph.list.write(ListWrite.Save(anime, planned)) }
        until { aniListAsked(id.value, failed) }
        until { id in waited { graph.airing.observeAiring().first() } }

        graph.scenarios.reset()
        until { !graph.scenarios.reset.value.busy }
        assertEquals(ResetState.Idle, graph.scenarios.reset.value)
        until { waited { graph.list.observeEntry(id).first() } == null }

        later()
        val since = graph.sandbox.aniList.asked.size
        waited { graph.list.write(ListWrite.Save(anime, planned)) }
        until { aniListAsked(id.value, since) }
    }

    // Past the client's spacing at each look, and more looks short of its cool-down than a test makes.
    private class SteppedTime : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
        val passed = AtomicLong()

        override fun read(): Long = passed.addAndGet(5.seconds.inWholeNanoseconds)
    }

    private class SwitchedDevice : Device {
        val online = MutableStateFlow(true)

        override fun isOnline() = online.value

        override fun observeOnline(): Flow<Boolean> = online

        override fun isMainThread() = Looper.getMainLooper().thread === Thread.currentThread()
    }

    private companion object {
        const val WAIT = 20_000L
        const val POLL = 20L
    }
}
