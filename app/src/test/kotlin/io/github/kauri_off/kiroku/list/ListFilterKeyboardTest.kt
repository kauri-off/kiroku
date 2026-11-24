package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.screenshots.ScreenshotRunner
import io.github.kauri_off.kiroku.screenshots.Shot
import io.github.kauri_off.kiroku.screenshots.SystemBars
import io.github.kauri_off.kiroku.screenshots.Variant
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListFilterKeyboardTest {
    @Test
    fun theEmptyFilterMessageClearsTheKeyboardInAWideWindow() {
        Shot
            .launch(Variant.RuLight, heightDp = 600, widthDp = 840, keyboard = true) {
                FixtureListScreen(userListNoMatches)
            }.use { shot ->
                val message = shot.visibleTextBounds("Нет тайтлов с таким названием")
                val keyboardTop = shot.windowHeightPx - shot.dpToPx(SystemBars.KEYBOARD_DP)
                assertTrue("Message $message overlaps the keyboard at $keyboardTop px", message.bottom <= keyboardTop)
            }
    }
}
