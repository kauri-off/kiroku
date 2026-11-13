package io.github.kauri_off.kiroku.design

import android.graphics.Rect
import android.os.Looper
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import io.github.kauri_off.kiroku.PATIENCE
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.time.toJavaDuration

// A wait draws its indicator only once it has outlasted the patience, so a short one never flashes it for a frame.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ScreenStateTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private var state by mutableStateOf<ScreenState>(ScreenState.Loading)

    private val indicator = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

    private fun indicators() = compose.onAllNodes(indicator).fetchSemanticsNodes().size

    // Time passes on the main thread's clock, which times the patience, a frame at a time.
    private fun pass(time: Duration) {
        repeat((time.toMillis() / FRAME).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME))
            compose.mainClock.advanceTimeByFrame()
        }
    }

    private lateinit var view: View

    private fun loading() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            view = LocalView.current
            KirokuTheme(dark = false, dynamicColor = false) { ScreenState(state) }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `a wait draws no indicator within the patience and draws it after`() {
        loading()
        assertEquals("indicators at once", 0, indicators())
        compose.onAllNodesWithContentDescription("Loading").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        pass(PATIENCE.toJavaDuration().minus(MARGIN))
        assertEquals("indicators within the patience", 0, indicators())
        pass(MARGIN.multipliedBy(2))
        assertEquals("indicators after the patience", 1, indicators())
    }

    // What a screen reader reads: Compose leaves a node of no size out of the accessibility tree.
    @Test
    fun `the wait is in the accessibility tree from its first frame`() {
        loading()
        val id = compose.onNode(hasContentDescription("Loading")).fetchSemanticsNode().id
        val info = checkNotNull(view.accessibilityNodeProvider?.createAccessibilityNodeInfo(id)) { "no accessibility node" }
        assertEquals("Loading", info.contentDescription)
        assertFalse(Rect().also(info::getBoundsInScreen).isEmpty)
        assertEquals("indicators", 0, indicators())
    }

    @Test
    fun `a wait that ends within the patience never draws the indicator`() {
        loading()
        pass(PATIENCE.toJavaDuration().minus(MARGIN))
        state = ScreenState.Empty("Nothing here")
        pass(MARGIN.multipliedBy(2))
        pass(PATIENCE.toJavaDuration())
        assertEquals(0, indicators())
    }

    private companion object {
        const val FRAME = 16L
        val MARGIN: Duration = Duration.ofMillis(64)
    }
}
