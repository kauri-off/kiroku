package io.github.kauri_off.kiroku.shell

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

// A view model belongs to its entry: one per place in a destination's stack, kept across switches and recreation.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class ShellScopingTest {
    private val compose = createAndroidComposeRule<ScopedShellActivity>()

    @get:Rule
    val rule: TestRule =
        RuleChain
            .outerRule(
                object : TestWatcher() {
                    override fun starting(description: Description) {
                        val app = RuntimeEnvironment.getApplication()
                        Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ScopedShellActivity::class.java))
                    }
                },
            ).around(compose)

    @Before
    fun reset() {
        Holder.cleared = 0
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun open() = compose.onNodeWithText("Open").performClick()

    private fun tap(times: Int) = repeat(times) { compose.onNode(hasText("Page", substring = true) and hasClickAction()).performClick() }

    private fun shows(text: String) = compose.onNodeWithText(text).assertExists()

    @Test
    fun theSameKeyInTwoDestinationsHasTwoViewModelsAndEachSurvivesSwitching() {
        open()
        tap(2)
        shows("Page 1: held 2, saved 2")
        tab("Search").performClick()
        open()
        shows("Page 1: held 0, saved 0")
        tap(1)
        tab("List").performClick()
        shows("Page 1: held 2, saved 2")
        tab("Search").performClick()
        shows("Page 1: held 1, saved 1")
        assertEquals(0, Holder.cleared)
    }

    @Test
    fun viewModelsSurviveRecreation() {
        open()
        tap(2)
        tab("Search").performClick()
        open()
        tap(1)
        compose.activityRule.scenario.recreate()
        shows("Page 1: held 1, saved 1")
        tab("List").performClick()
        shows("Page 1: held 2, saved 2")
        assertEquals(0, Holder.cleared)
    }

    @Test
    fun poppingAnEntryClearsItsViewModel() {
        open()
        shows("Page 1: held 0, saved 0")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        shows("List root")
        assertEquals(1, Holder.cleared)
    }

    @Test
    fun reselectingADestinationClearsTheViewModelsItPops() {
        open()
        tab("List").performClick()
        shows("List root")
        assertEquals(1, Holder.cleared)
        open()
        shows("Page 1: held 0, saved 0")
    }
}

class Holder : ViewModel() {
    var taps by mutableIntStateOf(0)

    override fun onCleared() {
        cleared++
    }

    companion object {
        var cleared = 0
    }
}

@Serializable
data class ScopedPage(
    val n: Int,
) : NavKey

class ScopedShellActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KirokuTheme(dark = false, dynamicColor = false) { ShellRoute(entries = entries) } }
    }

    private val entries: ShellEntries = { navigator, _ ->
        Destination.entries.forEach { destination ->
            entry(destination.root) {
                Column {
                    Text("${destination.name} root")
                    Button(onClick = { navigator.open(ScopedPage(1)) }) { Text("Open") }
                }
            }
        }
        entry<ScopedPage> { page ->
            val holder = viewModel { Holder() }
            var saved by rememberSaveable { mutableIntStateOf(0) }
            Button(onClick = {
                holder.taps++
                saved++
            }) { Text("Page ${page.n}: held ${holder.taps}, saved $saved") }
        }
    }
}
