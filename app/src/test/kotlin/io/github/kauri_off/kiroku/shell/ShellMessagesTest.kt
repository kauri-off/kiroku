package io.github.kauri_off.kiroku.shell

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// ARCHITECTURE 6.2: a message belongs to the shell, so it outlives the screen that posted it and a recreation.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ShellMessagesTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))
    private val messages = ShellMessages(scope, graph.list)
    private var composed by mutableStateOf(true)

    @After
    fun stop() = scope.cancel()

    // The shell's host, which a recreation makes anew.
    private fun shell() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                if (composed) {
                    val host = remember { SnackbarHostState() }
                    ShownMessages(messages, host)
                    SnackbarHost(host)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun recreate() {
        composed = false
        compose.waitForIdle()
        composed = true
        compose.waitForIdle()
    }

    private val anime = FixtureAnime.exactToWatch

    private fun watched() =
        runBlocking {
            graph.list
                .observeEntry(anime.id)
                .first()
                ?.values
                ?.watchedEpisodes
        }

    @Test
    fun `a message cut short by a recreation shows again, and its Undo still writes`() {
        shell()
        val before = runBlocking { graph.list.observeEntry(anime.id).first()!! }
        val change = runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }!!
        compose.runOnUiThread { messages.post(ShellMessage(ShellMessage.Text.EpisodeWatched(13), ShellMessage.Action.Undo(change.undo))) }
        compose.onNodeWithText("Episode\u00A013 watched").assertExists()
        recreate()
        compose.onNodeWithText("Episode\u00A013 watched").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(before.values.watchedEpisodes, watched())
        assertNull(messages.current.value)
    }

    @Test
    fun `a newer message replaces the one shown`() {
        shell()
        compose.runOnUiThread { messages.post(ShellMessage(ShellMessage.Text.EpisodeWatched(13))) }
        compose.runOnUiThread { messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(DataError.NoConnection))) }
        compose.waitForIdle()
        compose.onNodeWithText("No connection").assertExists()
        compose.onNodeWithText("Episode\u00A013 watched").assertDoesNotExist()
    }

    // W2: the failure is heard by the shell's view model, which lives through a recreation.
    @Test
    fun `a write failure that arrives while no screen is composed is shown afterwards`() {
        val shell = ShellViewModel(graph.list)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                if (composed) {
                    val host = remember { SnackbarHostState() }
                    ShownMessages(shell.messages, host)
                    SnackbarHost(host)
                }
            }
        }
        compose.waitForIdle()
        composed = false
        compose.waitForIdle()
        graph.demo.server.failingWrites.value = true
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        compose.waitForIdle()
        composed = true
        compose.waitForIdle()
        compose.onNodeWithText("Could not save").assertExists()
        compose.onNodeWithText("Retry").assertExists()
    }

    private val moved = ShellMessage(ShellMessage.Text.MovedToCompleted, ShellMessage.Action.Undo(ListWrite.Remove(anime)))

    private fun watched(episode: Int) =
        ShellMessage(ShellMessage.Text.EpisodeWatched(episode), ShellMessage.Action.Undo(ListWrite.Remove(anime)))

    private fun failed(episode: Int) =
        ShellMessage(ShellMessage.Text.CouldNotSave, ShellMessage.Action.Retry(ListWrite.SetWatched(anime, episode)))

    // Posts [posted] in order, then lets each shown message time out; returns what was shown, in order.
    private fun shownAfter(vararg posted: ShellMessage): List<ShellMessage> {
        posted.forEach(messages::post)
        return generateSequence {
            messages.current.value
                ?.also { messages.ended(it, actionPerformed = false) }
                ?.message
        }.toList()
    }

    @Test
    fun `ordinary messages replace each other, so quick taps on several rows build no queue`() {
        assertEquals(listOf(watched(3)), shownAfter(watched(1), watched(2), watched(3)))
    }

    // An ordinary message's row already shows what happened; held back, its Undo would come long after its tap.
    @Test
    fun `the Undo of a title that left its tab is not replaced, and an ordinary message meanwhile is dropped`() {
        assertEquals(listOf(moved), shownAfter(moved, watched(2), watched(3)))
        assertEquals(listOf(moved), shownAfter(watched(1), moved))
    }

    @Test
    fun `two messages that must stay are shown one after the other`() {
        assertEquals(listOf(failed(1), moved, failed(2)), shownAfter(failed(1), watched(3), moved, failed(2), watched(4)))
    }

    // B3's m1: a stale Undo would take back a step the user had already taken back; a stale Retry would overwrite.
    @Test
    fun `a waiting Undo or Retry is dropped once its title has been written, and one whose title has not is shown`() {
        val other = FixtureAnime.oneShortOfTotal
        val otherMoved = ShellMessage(ShellMessage.Text.MovedToCompleted, ShellMessage.Action.Undo(ListWrite.Remove(other)))
        compose.runOnUiThread { listOf(moved, failed(1), otherMoved, moved).forEach(messages::post) }
        compose.waitForIdle()
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        compose.waitForIdle()
        val shown =
            compose.runOnUiThread<List<ShellMessage>> {
                generateSequence {
                    messages.current.value
                        ?.also { messages.ended(it, actionPerformed = false) }
                        ?.message
                }.toList()
            }
        assertEquals(listOf(moved, otherMoved), shown)
    }

    @Test
    fun `an Undo that adds nothing back, as an episode watched, does not have to stay`() {
        assertEquals(false, watched(1).mustStay)
        assertEquals(false, ShellMessage(ShellMessage.Text.AddedToPlanned, ShellMessage.Action.Change(anime.id)).mustStay)
        assertEquals(false, ShellMessage(ShellMessage.Text.RefreshFailed(DataError.NoConnection)).mustStay)
    }

    // A save that fails for the session cannot succeed by a Retry; the session banner offers sign-in instead.
    @Test
    fun `a save that fails for the expired session offers no Retry`() {
        val shell = ShellViewModel(graph.list)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val host = remember { SnackbarHostState() }
                ShownMessages(shell.messages, host)
                SnackbarHost(host)
            }
        }
        compose.waitForIdle()
        graph.demo.server.sessionExpired.value = true
        compose.waitForIdle()
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        compose.waitForIdle()
        compose.onNodeWithText("Could not save").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        assertNull(
            shell.messages.current.value
                ?.message
                ?.action,
        )
    }

    // W2: a later "+1" on another title does not take the failed save's Retry away.
    @Test
    fun `a failed save keeps its Retry through a newer message, and Retry writes`() {
        val shell = ShellViewModel(graph.list)
        val other = FixtureAnime.oneShortOfTotal
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val host = remember { SnackbarHostState() }
                ShownMessages(shell.messages, host)
                SnackbarHost(host)
            }
        }
        compose.waitForIdle()
        val before = watched()
        graph.demo.server.failingWrites.value = true
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        compose.waitForIdle()
        graph.demo.server.failingWrites.value = false
        compose.onNodeWithText("Could not save").assertExists()
        assertEquals(before, watched())
        val change = runBlocking { graph.list.write(ListWrite.StepWatched(other, -1)) }!!
        compose.runOnUiThread {
            shell.messages.post(ShellMessage(ShellMessage.Text.EpisodeWatched(10), ShellMessage.Action.Undo(change.undo)))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Could not save").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(before!! + 1, watched())
        compose.onNodeWithText("Episode\u00A010 watched").assertDoesNotExist()
    }
}
