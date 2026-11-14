package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.design.component.DrawnTitleKey
import io.github.kauri_off.kiroku.design.component.RowLine
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.screenshots.Variant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// ARCHITECTURE 9.3: line 2 with the value a tab is sorted by is one line on a 411 dp phone, "+1" and line 3 present.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListLineTwoTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private var shown by mutableStateOf<Pair<ListState, Float>?>(null)

    // The widest rows: the long show first, then the rows the first screen shows beside it.
    private fun widest(sort: ListSort): ListState {
        val rows =
            ListFixtureStates
                .sorted(ListFixtureStates.watching.tab, sort)
                .page!!
                .rows
                .filter { it.sortedBy != null }
                .sortedByDescending { it.id == FixtureAnime.exactToWatch.id }
                .take(ROWS)
        return ListFixtureStates.watching.copy(
            content = ListFixtureStates.tabs(override = ListFixtureStates.watchingPage(rows, sort)),
        )
    }

    // Lines of [line] as drawn; a scored line, spoken otherwise, is found by what it says.
    private fun lineCount(line: RowLine): Int {
        val separator = compose.activity.getString(R.string.common_separator)
        val said = line.score?.let { line.text + separator + it.spoken } ?: line.text
        val node = compose.onAllNodesWithText(said, useUnmergedTree = true)[0].fetchSemanticsNode()
        if (DrawnTitleKey in node.config) return checkNotNull(node.config[DrawnTitleKey].layout).lineCount
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single().lineCount
    }

    private fun check(scales: List<Float>) {
        compose.setContent {
            val (state, scale) = shown ?: return@setContent
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                KirokuTheme(dark = false, dynamicColor = false) { FixtureListScreen(state) }
            }
        }
        val problems = mutableListOf<String>()
        for (sort in listOf(ListSort.Score, ListSort.StartDate)) {
            val state = widest(sort)
            val rows = state.page!!.rows
            assertEquals(FixtureAnime.exactToWatch.id, rows.first().id)
            for (scale in scales) {
                compose.runOnIdle { shown = state to scale }
                compose.waitForIdle()
                val text = ListText(compose.activity, state.now)
                for (row in rows) {
                    val line = text.line2(row.line2, row.sortedBy)
                    val lines = lineCount(line)
                    if (lines != 1) problems += "$sort at $scale: $line takes $lines lines"
                }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `the registry's variants at font scale 1 are the ones checked here`() {
        val variants = Variant.entries.filter { it.fontScale == 1f }
        assertEquals(setOf(WIDTH), variants.map { it.widthDp }.toSet())
        assertEquals(setOf("en", "ru"), variants.map { it.language }.toSet())
    }

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xhdpi")
    fun `in English the sorted value keeps line 2 on one line, also at the larger font scale`() = check(listOf(1f, LARGER))

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp-xhdpi")
    fun `in Russian the sorted value keeps line 2 on one line`() = check(listOf(1f))

    private companion object {
        const val WIDTH = 411
        const val ROWS = 4
        const val LARGER = 1.3f
    }
}
