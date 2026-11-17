package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.profile.ProfileNotBuilt
import io.github.kauri_off.kiroku.profile.ProfileNotBuiltKey
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.OpenedPage
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A fling down another user's list of several thousand titles composes only the rows that come into view.
@OptIn(ExperimentalCoroutinesApi::class, InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class UserListFrameWorkTest {
    private val main = StandardTestDispatcher()
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = RuleChain.outerRule(MainDispatcherRule(main)).around(registered(compose))

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))

    private val counts = mutableMapOf<String, Int>()
    private var counting = false

    private val tracer =
        object : CompositionTracer {
            override fun isTraceInProgress() = true

            override fun traceEventStart(
                key: Int,
                dirty1: Int,
                dirty2: Int,
                info: String,
            ) {
                if (counting) counts.merge(info.substringBefore(" ("), 1, Int::plus)
            }

            override fun traceEventEnd() = Unit
        }

    @After
    fun stop() {
        Composer.setTracer(null)
        scope.cancel()
    }

    private fun settle() =
        repeat(SETTLE_ROUNDS) {
            main.scheduler.advanceUntilIdle()
            compose.waitForIdle()
        }

    private fun frame(): Map<String, Int> {
        counts.clear()
        counting = true
        main.scheduler.runCurrent()
        compose.mainClock.advanceTimeByFrame()
        main.scheduler.runCurrent()
        counting = false
        return counts.toMap()
    }

    @Test
    fun `a fling down the longest tab composes no page and no screen part, and in each frame only the rows coming into view`() {
        val name = Fixtures.reference.otherUserName
        Composer.setTracer(tracer)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                AppRoute(graph, rememberShellNavigator(OpenedPage(Destination.Profile, ProfileNotBuiltKey(ProfileNotBuilt.UserList, name))))
            }
        }
        settle()
        compose.onNode(hasText("Completed") and isSelectable()).performClick()
        settle()
        compose.onNode(hasText("Completed") and isSelectable()).assertIsSelected()
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.9f, endY = top + height * 0.2f, durationMillis = FLING_MILLIS) }
        val frames = (1..FLING_FRAMES).map { frame() }
        val rows = frames.map { it[ROW] ?: 0 }
        assertTrue("the fling moved the list: $rows", rows.sum() > ROWS_ON_A_SCREEN)
        frames.forEachIndexed { index, frame ->
            assertEquals("frame $index", emptyMap<String, Int>(), frame.filterKeys { it in screenParts })
            assertTrue("frame $index composed ${rows[index]} rows", rows[index] <= ROWS_ON_A_SCREEN)
        }
    }

    private companion object {
        const val SETTLE_ROUNDS = 5
        const val FLING_MILLIS = 80L
        const val FLING_FRAMES = 90

        // About 891 dp over the shortest row, with the prefetched one.
        const val ROWS_ON_A_SCREEN = 12
        const val ROW = "io.github.kauri_off.kiroku.design.component.AnimeRow"

        val screenParts =
            setOf(
                "io.github.kauri_off.kiroku.list.ListScreen",
                "io.github.kauri_off.kiroku.list.Page",
                "io.github.kauri_off.kiroku.list.Rows",
                "io.github.kauri_off.kiroku.list.ListTabs",
                "io.github.kauri_off.kiroku.list.TopBar",
            )
    }
}
