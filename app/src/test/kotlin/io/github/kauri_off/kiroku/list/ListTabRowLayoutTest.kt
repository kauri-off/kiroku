package io.github.kauri_off.kiroku.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.Sizes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A frame of a swipe places the tab row again and measures only its indicator.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ListTabRowLayoutTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    fun `a frame of a swipe measures the indicator and not the row`() {
        var rowMeasures = 0
        var barMeasures = 0
        lateinit var pager: PagerState
        lateinit var scope: CoroutineScope
        compose.setContent {
            pager = rememberPagerState { LABELS.size }
            scope = rememberCoroutineScope()
            val motion = rememberListTabMotion(pager)
            val row = remember(motion) { TabRowLayout(motion) }
            val counted =
                remember(row) {
                    object : MultiContentMeasurePolicy {
                        override fun MeasureScope.measure(
                            measurables: List<List<Measurable>>,
                            constraints: Constraints,
                        ): MeasureResult {
                            rowMeasures++
                            return with(row) { measure(measurables, constraints) }
                        }
                    }
                }
            Column {
                Layout(
                    contents =
                        listOf(
                            { LABELS.forEach { Text(it, Modifier.padding(horizontal = Sizes.tabLabelPadding)) } },
                            {
                                Box(
                                    row.indicator
                                        .layout { measurable, constraints ->
                                            barMeasures++
                                            val bar = measurable.measure(constraints)
                                            layout(bar.width, bar.height) { bar.place(0, 0) }
                                        }.height(Sizes.tabRowEdge),
                                )
                            },
                        ),
                    measurePolicy = counted,
                )
                HorizontalPager(pager, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        val rows = rowMeasures
        val bars = barMeasures
        for (step in 1..STEPS) {
            val position = step.toFloat() / STEPS * (LABELS.size - 1)
            val page = Math.round(position)
            compose.runOnIdle { scope.launch { pager.scrollToPage(page, position - page) } }
            compose.waitForIdle()
        }
        assertEquals(LABELS.lastIndex, pager.currentPage)
        assertEquals("the row's measures", rows, rowMeasures)
        assertTrue("the indicator's measures: $bars, then $barMeasures", barMeasures >= bars + STEPS)
    }

    private companion object {
        const val STEPS = 12
        val LABELS = listOf("A", "Bbbbbb", "Cc", "Dddddddddd", "Eee")
    }
}
