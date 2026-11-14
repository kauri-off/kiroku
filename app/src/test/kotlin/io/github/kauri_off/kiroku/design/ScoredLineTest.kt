package io.github.kauri_off.kiroku.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.scorePlaceholder
import io.github.kauri_off.kiroku.design.component.scoredLineText
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// A line that ends in the score wraps before the score's "·", so the dot, the star and the number stay together.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp")
class ScoredLineTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    fun `a full line wraps before the dot in front of the score`() {
        lateinit var text: AnnotatedString
        lateinit var layout: TextLayoutResult
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val measurer = rememberTextMeasurer()
                val style = MaterialTheme.typography.bodyMedium
                val facts = "TV\u00A0· 2023\u00A0· 24\u00A0episodes"
                text = scoredLineText(facts, "8.55", stringResource(R.string.design_score_separator))
                val star = text.getStringAnnotations(INLINE_CONTENT, 0, text.length).single()
                val width = measurer.measure(facts, style).size.width + SLACK_PX
                layout =
                    measurer.measure(
                        text,
                        style,
                        constraints = Constraints(maxWidth = width),
                        placeholders = listOf(AnnotatedString.Range(scorePlaceholder, star.start, star.end)),
                    )
            }
        }
        compose.waitForIdle()
        val lines = (0 until layout.lineCount).map { text.substring(layout.getLineStart(it), layout.getLineEnd(it)) }
        assertEquals(2, lines.size)
        lines.forEach { assertFalse(lines.toString(), it.trimEnd().endsWith('·')) }
        assertTrue(lines.toString(), lines.last().startsWith('·'))
    }

    private companion object {
        const val INLINE_CONTENT = "androidx.compose.foundation.text.inlineContent"
        const val SLACK_PX = 4
    }
}
