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
        assertNull(messages.current)
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
        val shell = ShellViewModel(graph)
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
}
