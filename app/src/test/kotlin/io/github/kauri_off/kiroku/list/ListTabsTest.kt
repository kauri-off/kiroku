package io.github.kauri_off.kiroku.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.text.label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// At rest the List's tab row is the stock Expressive scrollable tab row: same tabs, indicator and scroll, to the pixel.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListTabsTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    @Config(qualifiers = "en-w411dp-h891dp")
    fun `at rest on each tab the row is the stock one`() = assertStockOnEveryTab()

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp")
    fun `at rest on each tab the Russian row is the stock one`() = assertStockOnEveryTab()

    @Test
    @Config(qualifiers = "en-w411dp-h891dp", fontScale = LARGE_TEXT)
    fun `at rest on each tab the row at large text is the stock one`() = assertStockOnEveryTab()

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp", fontScale = LARGE_TEXT)
    fun `at rest on each tab the Russian row at large text is the stock one`() = assertStockOnEveryTab()

    // The ldrtl qualifier leaves the layout direction left to right under Robolectric, so it is provided.
    @Test
    @Config(qualifiers = "en-w411dp-h891dp")
    fun `at rest on each tab the right-to-left row is the stock one`() = assertStockOnEveryTab(LayoutDirection.Rtl)

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp")
    fun `at rest on each tab the right-to-left Russian row is the stock one`() = assertStockOnEveryTab(LayoutDirection.Rtl)

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp", fontScale = LARGE_TEXT)
    fun `at rest on each tab the right-to-left Russian row at large text is the stock one`() = assertStockOnEveryTab(LayoutDirection.Rtl)

    private fun assertStockOnEveryTab(direction: LayoutDirection = LayoutDirection.Ltr) {
        val statuses = ListStatus.entries
        var selected by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    key(selected) {
                        Column {
                            Box(Modifier.testTag(STOCK)) { StockRow(selected) }
                            Box(Modifier.testTag(OURS)) {
                                ListTabs(rememberListTabMotion(rememberPagerState(initialPage = selected) { statuses.size })) {}
                            }
                        }
                    }
                }
            }
        }
        val indicatorWidths = mutableSetOf<Float>()
        val firstTabStarts = mutableSetOf<Float>()
        for (index in statuses.indices) {
            selected = index
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(SETTLED)
            val stock = bounds(hasTestTag(STOCK))
            val ours = bounds(hasTestTag(OURS))
            assertClose("row height", stock.bottom - stock.top, ours.bottom - ours.top)
            val label = statuses.map { compose.activity.getString(it.label()) }
            label.forEach { text ->
                val a = tabIn(STOCK, text)
                val b = tabIn(OURS, text)
                assertClose("$text left, tab $index", a.left - stock.left, b.left - ours.left)
                assertClose("$text right, tab $index", a.right - stock.left, b.right - ours.left)
                assertClose("$text height, tab $index", a.bottom - a.top, b.bottom - b.top)
            }
            val shownStock = bounds(hasTestTag(STOCK_INDICATOR), unmerged = true)
            val shownOurs = bounds(hasTestTag(INDICATOR), unmerged = true)
            assertClose("indicator left, tab $index", shownStock.left - stock.left, shownOurs.left - ours.left)
            assertClose("indicator right, tab $index", shownStock.right - stock.left, shownOurs.right - ours.left)
            assertClose("indicator top, tab $index", shownStock.top - stock.top, shownOurs.top - ours.top)
            assertClose("indicator height, tab $index", shownStock.bottom - shownStock.top, shownOurs.bottom - shownOurs.top)
            indicatorWidths += (shownOurs.right - shownOurs.left).value
            firstTabStarts += (tabIn(OURS, label.first()).left - ours.left).value
            compose.onNode(hasText(label[index]) and isSelected() and hasAnyAncestor(hasTestTag(OURS))).assertExists()
        }
        // Labels of different lengths give indicators of different widths, so text was measured; the row scrolled.
        assertTrue("indicator widths $indicatorWidths", indicatorWidths.size > 1)
        assertTrue("first tab starts $firstTabStarts", firstTabStarts.size > 1)
    }

    private fun bounds(
        matcher: SemanticsMatcher,
        unmerged: Boolean = false,
    ): DpRect = compose.onNode(matcher, useUnmergedTree = unmerged).getUnclippedBoundsInRoot()

    private fun tabIn(
        tag: String,
        text: String,
    ): DpRect = bounds(hasText(text) and isSelectable() and hasAnyAncestor(hasTestTag(tag)))

    private fun assertClose(
        what: String,
        expected: Dp,
        actual: Dp,
    ) = assertEquals(what, expected.value, actual.value, TOLERANCE)

    // The stock row with its default indicator, tagged, and the List's edge.
    @Composable
    private fun StockRow(selected: Int) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selected,
            edgePadding = Sizes.tabRowEdge,
            indicator = {
                TabRowDefaults.PrimaryIndicator(
                    Modifier.tabIndicatorOffset(selected, matchContentSize = true).testTag(STOCK_INDICATOR),
                    width = Dp.Unspecified,
                )
            },
        ) {
            ListStatus.entries.forEachIndexed { index, status ->
                Tab(selected = index == selected, onClick = {}, text = { Text(stringResource(status.label())) })
            }
        }
    }

    private companion object {
        const val STOCK = "stock"
        const val OURS = "ours"
        const val STOCK_INDICATOR = "stockIndicator"
        const val SETTLED = 3_000L
        const val TOLERANCE = 1f
        const val LARGE_TEXT = 2f
    }
}
