package io.github.kauri_off.kiroku.source

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
    fun `the app starts again on its first screen, in a task that holds nothing of the run before`() {
        val intent = relaunchIntent(context)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(flags, intent.flags and flags)
    }
}
