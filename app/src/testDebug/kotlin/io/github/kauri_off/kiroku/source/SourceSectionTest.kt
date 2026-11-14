package io.github.kauri_off.kiroku.source

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SourceSectionTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = RuleChain.outerRule(StartsOn(DataSource.Account)).around(registered(compose))

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `Restart is offered once another source than the running one is chosen, and asks for the app to start again`() {
        val restarts = mutableListOf<Context>()
        compose.setContent { Column { SourceRoute(restart = { restarts += it }) } }
        compose.onNodeWithText("Restart").assertDoesNotExist()

        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("Still on MyAnimeList until the app starts again").assertExists()
        compose.onNodeWithText("Restart").performClick()

        assertEquals(1, restarts.size)
        assertEquals(DataSource.Demo, SourceChoice(context).chosen())
    }

    @Test
    fun `before the process ends the session's file gets its try, then the first screen is asked for`() {
        val events = mutableListOf<String>()
        val started = { shadowOf(context).peekNextStartedActivity()?.component?.className }
        restartApp(context, beforeExit = { events += "write, started ${started()}" }, exit = { events += "exit, started ${started()}" })
        assertEquals(listOf("write, started null", "exit, started ${MainActivity::class.java.name}"), events)
    }

    @Test
    fun `a write that does not end does not keep the app from starting again`() {
        var exits = 0
        restartApp(context, patience = 50.milliseconds, beforeExit = { awaitCancellation() }, exit = { exits++ })
        assertEquals(1, exits)
    }

    @Test
    fun `the app starts again on its first screen, in a task that holds nothing of the run before`() {
        val intent = relaunchIntent(context)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(flags, intent.flags and flags)
    }
}
