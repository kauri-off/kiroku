package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The bar keeps the room of its longest line, so a read that starts or ends moves no row, at any text size.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserListBarTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private var shown by mutableStateOf(UserListFixtureStates.watching)

    @Test
    @Config(qualifiers = "en-w411dp-h891dp")
    fun `a read moves no row`() = assertNoRowMoves()

    @Test
    @Config(qualifiers = "ru-w360dp-h640dp", fontScale = STRESS_TEXT)
    fun `a read moves no row in Russian at large text on a narrow screen`() = assertNoRowMoves()

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp", fontScale = LARGE_TEXT)
    fun `a read moves no row in Russian at the largest text`() = assertNoRowMoves()

    private fun assertNoRowMoves() {
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { FixtureListScreen(shown) } }
        compose.waitForIdle()
        val top = firstRowTop()
        for (state in reading) {
            compose.runOnIdle { shown = state }
            compose.waitForIdle()
            assertEquals(state.reading.toString(), top, firstRowTop())
        }
    }

    private fun firstRowTop() =
        compose
            .onNode(
                hasText(FixtureAnime.exactToWatch.titles.romaji, substring = true) and hasClickAction(),
            ).getUnclippedBoundsInRoot()
            .top

    private companion object {
        const val STRESS_TEXT = 1.3f
        const val LARGE_TEXT = 2f

        val reading =
            with(UserListFixtureStates) {
                listOf(reading, readingWithTotal, readAgain, readAgainPart, watching)
            }
    }
}
