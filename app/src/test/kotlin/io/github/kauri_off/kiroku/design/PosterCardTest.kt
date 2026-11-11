package io.github.kauri_off.kiroku.design

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.kauri_off.kiroku.design.component.PosterCard
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// A caption of a strip card is never broken inside a word, at any font scale: the card grows with it.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterCardTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private var shown by mutableStateOf<Triple<Float, String, String>?>(null)

    private val scales = listOf(1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)

    // The longest single words among the captions, in both languages, and two-word captions.
    private val captions1 =
        listOf("Продолжение", "Предыстория", "Побочная история", "Основная история", "Краткое изложение", "Альт. сеттинг", "Parent story")
    private val captions2 = listOf("Запланировано", "Просмотрено", "Отложено", "Completed", "Фильм · 2024")

    @Test
    fun captionsAreNeverBrokenInsideAWord() {
        for (scale in scales) {
            for ((caption1, caption2) in captions1.zip(captions2 + captions2)) {
                val layouts = render(scale, caption1, caption2)
                val first = layouts.getValue(caption1)
                assertTrue("$caption1 at $scale", first.lineCount <= 2)
                assertWholeWords(caption1, first, scale)
                val second = layouts.getValue(caption2)
                assertEquals("$caption2 at $scale", 1, second.lineCount)
                assertWholeWords(caption2, second, scale)
            }
        }
    }

    private fun assertWholeWords(
        text: String,
        layout: TextLayoutResult,
        scale: Float,
    ) {
        assertFalse("$text is cut at $scale", layout.isLineEllipsized(layout.lineCount - 1) || layout.didOverflowHeight)
        // Only a space counts as a break between words, so «Спин-офф» breaking after its hyphen would fail here too.
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue("$text breaks inside a word at $scale", text[end - 1] == ' ' || text[end] == ' ')
        }
    }

    private fun render(
        scale: Float,
        caption1: String,
        caption2: String,
    ): Map<String, TextLayoutResult> {
        if (shown == null) {
            compose.setContent {
                val shown = shown ?: return@setContent
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, shown.first)) {
                    KirokuTheme(dark = false, dynamicColor = false) {
                        Row(Modifier.verticalScroll(rememberScrollState())) {
                            PosterCard("Kagerou Toshokan", shown.second, shown.third, posterUrl = null, onClick = {})
                        }
                    }
                }
            }
        }
        compose.runOnIdle { shown = Triple(scale, caption1, caption2) }
        compose.waitForIdle()
        return listOf(caption1, caption2).associateWith { text ->
            val results = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action!!
                .invoke(results)
            results.single()
        }
    }
}
