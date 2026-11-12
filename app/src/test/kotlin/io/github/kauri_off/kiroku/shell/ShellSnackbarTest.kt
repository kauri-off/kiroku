package io.github.kauri_off.kiroku.shell

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.design.theme.ThemeSettings
import io.github.kauri_off.kiroku.design.theme.setKirokuContent
import io.github.kauri_off.kiroku.screenshots.SystemBars
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
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

// The shell's one snackbar host is drawn on every destination, above the bar or beside the rail, clear of the gesture bar.
@RunWith(RobolectricTestRunner::class)
class ShellSnackbarTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule =
        RuleChain
            .outerRule(
                object : TestWatcher() {
                    override fun starting(description: Description) {
                        SystemBars.simulate()
                        val app = RuntimeEnvironment.getApplication()
                        Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                    }
                },
            ).around(compose)

    private val snackbars = SnackbarHostState()
    private val scope = MainScope()

    @After
    fun stop() = scope.cancel()

    private fun shell() {
        compose.runOnUiThread {
            compose.activity.setKirokuContent(
                ThemeSettings(dynamicColor = false),
            ) { ShellRoute(snackbars = snackbars) }
        }
        compose.waitForIdle()
        scope.launch { snackbars.showSnackbar("Could not save", "Retry", duration = SnackbarDuration.Indefinite) }
        compose.waitForIdle()
    }

    private fun bounds(matcher: SemanticsMatcher): DpRect = compose.onNode(matcher).getUnclippedBoundsInRoot()

    private val snackbar = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)
    private val suite = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)

    private fun clearOfTheGestureBar(snackbar: DpRect) {
        val window = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("$snackbar in $window", snackbar.bottom <= window.bottom - SystemBars.HEIGHT_DP.dp)
    }

    @Test
    @Config(qualifiers = "en-w411dp-h891dp")
    fun onAPhoneTheSnackbarSitsAboveTheBarOnEveryDestination() {
        shell()
        Destination.entries.forEach { destination ->
            compose.onNode(hasText(compose.activity.getString(destination.label)) and hasClickAction()).performClick()
            val shown = bounds(snackbar)
            val bar = bounds(suite)
            assertTrue("${destination.name}: $shown over $bar", shown.bottom <= bar.top)
            clearOfTheGestureBar(shown)
        }
    }

    @Test
    @Config(qualifiers = "en-w840dp-h600dp")
    fun onAWideWindowTheSnackbarSitsBesideTheRailOnEveryDestination() {
        shell()
        Destination.entries.forEach { destination ->
            compose.onNode(hasText(compose.activity.getString(destination.label)) and hasClickAction()).performClick()
            val shown = bounds(snackbar)
            val rail = bounds(suite)
            assertTrue("${destination.name}: $shown over $rail", shown.left >= rail.right)
            clearOfTheGestureBar(shown)
        }
    }
}
