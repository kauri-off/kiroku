package io.github.kauri_off.kiroku.sandbox

import android.os.Looper
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaListReader
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.KeysInMemory
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SandboxMangaRouteTest {
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

    @get:Rule(order = 1)
    val network = DeviceOnline()

    @get:Rule(order = 2)
    val source = StartsOn(DataSource.Sandbox)

    @get:Rule(order = 2)
    val time = MovedClock()

    @get:Rule(order = 3)
    val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = RuntimeEnvironment.getApplication().appGraph as SandboxGraph
    private val manga: MangaListReader get() = graph.mangaList

    private fun <T> waited(block: suspend () -> T): T = runBlocking { withTimeout(WAIT) { block() } }

    private fun shows(text: String) =
        compose.waitUntil(WAIT) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun openList() {
        shows("Manga")
        compose.onNodeWithText("Manga").performClick()
        shows("My manga list")
    }

    private fun signIn(): Session.SignedIn =
        waited {
            graph.signIn.start { error("Sandbox sign-in opened a browser") }
            val step = graph.signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed }
            assertEquals(SignInStep.SignedIn, step)
            graph.session.observeSession().first { it is Session.SignedIn } as Session.SignedIn
        }

    @Test
    fun `sandbox list retries offline and opens a manga title with Back to the cached list`() {
        val account = signIn()
        graph.scenarios.set(Scenario(offline = true))
        openList()
        shows("No connection")
        shows("Retry")
        compose.onNodeWithText("Retry").performClick()
        shows("No connection")

        graph.scenarios.set(Scenario())
        shows("Paper Observatory")
        val before = waited { manga.stored(account.signIn) }
        graph.scenarios.set(Scenario(offline = true))
        compose.onNodeWithText("Paper Observatory").performClick()
        compose.onNode(isHeading() and hasText("Paper Observatory")).assertExists()
        shows("No connection")
        compose.onNodeWithText("Manga\u00A0· Finished").assertDoesNotExist()
        graph.scenarios.set(Scenario())
        shows("Manga\u00A0· Finished")
        compose.activityRule.scenario.recreate()
        shows("Manga\u00A0· Finished")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        shows("My manga list")
        shows("Paper Observatory")

        assertEquals(before, waited { manga.stored(account.signIn) })
        graph.scenarios.set(Scenario(offline = true))
        val list = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("The Lantern District")))
        list.performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        shows("No connection")
        shows("Paper Observatory")
        assertEquals(before, waited { manga.stored(account.signIn) })
        compose.onNodeWithText("Paper Observatory").performClick()
        shows("Manga\u00A0· Finished")
        graph.scenarios.set(Scenario())
        assertEquals(emptyList<Any>(), connections.asked)
    }

    @Test
    fun `sandbox sign out closes the manga route and the next sign-in builds its own snapshot`() {
        val old = signIn()
        openList()
        shows("Paper Observatory")
        val before = (waited { manga.stored(old.signIn) } as Outcome.Success).value
        waited { graph.signOut(old) }
        shows("My list")
        compose.onNodeWithText("Paper Observatory").assertDoesNotExist()

        val next = signIn()
        assertNotEquals(old.owner, next.owner)
        assertEquals(Outcome.Failure(DataError.ListEnded), waited { manga.stored(old.signIn) })
        assertEquals(Outcome.Success(null), waited { manga.stored(next.signIn) })
        time.ahead = Duration.ofMinutes(1)
        openList()
        shows("Paper Observatory")
        val after = (waited { manga.stored(next.signIn) } as Outcome.Success).value
        assertEquals(next.owner, after?.owner)
        assertEquals(before?.entries, after?.entries)
        assertEquals(emptyList<Any>(), connections.asked)
    }

    @Test
    fun `the expiry scenario expires a manga read but keeps its cached snapshot`() {
        val account = signIn()
        openList()
        shows("Paper Observatory")
        val before = waited { manga.stored(account.signIn) }

        graph.scenarios.set(Scenario(sessionExpired = true))
        val list = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("The Lantern District")))
        list.performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        shows("Session expired")
        assertEquals(Session.Expired(account.signIn), waited { graph.session.observeSession().first() })
        assertEquals(before, waited { manga.stored(account.signIn) })
        shows("Paper Observatory")
        assertEquals(emptyList<Any>(), connections.asked)
    }

    private companion object {
        const val WAIT = 20_000L
    }
}
