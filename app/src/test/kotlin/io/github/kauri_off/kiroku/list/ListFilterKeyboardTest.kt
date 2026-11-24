package io.github.kauri_off.kiroku.list

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.kauri_off.kiroku.screenshots.ScreenshotRunner
import io.github.kauri_off.kiroku.screenshots.Shot
import io.github.kauri_off.kiroku.screenshots.SystemBars
import io.github.kauri_off.kiroku.screenshots.Variant
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellScreen
import io.github.kauri_off.kiroku.shell.ShellState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListFilterKeyboardTest {
    @Test
    fun theCompactShellAndKeyboardClipTheViewportOnlyOnce() {
        fun measure(keyboard: Boolean): Pair<Float, Float> {
            var viewportBottom = 0f
            Shot
                .launch(Variant.RuLight, heightDp = 550, widthDp = 411, keyboard = keyboard) {
                    ShellScreen(ShellState(Destination.Profile), {}) {
                        ListScreen(
                            userListNoMatches,
                            {},
                            SnackbarHostState(),
                            Modifier.onGloballyPositioned { viewportBottom = it.boundsInWindow().bottom },
                        )
                    }
                }.use { shot ->
                    val message = shot.visibleTextBounds("Нет тайтлов с таким названием")
                    val keyboardTop = shot.windowHeightPx - shot.dpToPx(SystemBars.KEYBOARD_DP)
                    assertTrue(viewportBottom > keyboardTop)
                    return message.center.y to (viewportBottom - keyboardTop)
                }
        }
        val (restingCenter, overlap) = measure(keyboard = false)
        val (typingCenter, _) = measure(keyboard = true)
        assertEquals(restingCenter - overlap / 2, typingCenter, 1f)
    }

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
