package io.github.kauri_off.kiroku.list

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.manga.MangaListState
import io.github.kauri_off.kiroku.manga.mangaListFixture
import io.github.kauri_off.kiroku.screenshots.ScreenshotRunner
import io.github.kauri_off.kiroku.screenshots.Shot
import io.github.kauri_off.kiroku.screenshots.SystemBars
import io.github.kauri_off.kiroku.screenshots.Variant
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellScreen
import io.github.kauri_off.kiroku.shell.ShellState
import io.github.kauri_off.kiroku.shell.show
import io.github.kauri_off.kiroku.title.TitleFixtures
import io.github.kauri_off.kiroku.title.TitleScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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

    @Test
    fun theMangaEmptyFilterClearsTheKeyboardInCompactAndWideWindows() {
        val state = MangaListState(content = mangaListFixture(), filter = ListState.Typed("xyz"), matches = emptyList())
        for ((width, height) in listOf(360 to 550, 840 to 600)) {
            Shot
                .launch(Variant.RuLight, heightDp = height, widthDp = width, keyboard = true) {
                    ShellScreen(ShellState(Destination.List), {}) {
                        ListScreen(ListPane.Manga(state, {}), SnackbarHostState(), onMode = {})
                    }
                }.use { shot ->
                    val message = shot.visibleTextBounds("Нет тайтлов с таким названием")
                    val keyboardTop = shot.windowHeightPx - shot.dpToPx(SystemBars.KEYBOARD_DP)
                    assertTrue("Message $message overlaps the keyboard at $keyboardTop px", message.bottom <= keyboardTop)
                }
        }
    }

    @Test
    fun everyMatchCanScrollClearOfTheKeyboardInACompactShellAndAWideWindow() {
        val state = ListFixtureStates.filtering
        val last = state.matches!!.last().title
        for ((width, height) in listOf(411 to 550, 840 to 600)) {
            Shot
                .launch(Variant.RuLight, heightDp = height, widthDp = width, keyboard = true) {
                    ShellScreen(ShellState(Destination.List), {}) { FixtureListScreen(state) }
                }.use { shot ->
                    shot.scrollRangeDp()
                    val keyboardTop = shot.windowHeightPx - shot.dpToPx(SystemBars.KEYBOARD_DP)
                    assertTrue(shot.visibleTextBounds(last).bottom <= keyboardTop)
                    assertTrue(shot.scrolledContentBottomPx() <= keyboardTop)
                }
        }
    }

    @Test
    fun completionAndRetryActionsClearTheKeyboardInCompactAndRailWindows() {
        for ((width, height) in listOf(411 to 550, 840 to 600)) {
            for ((message, action) in listOf("Moved to Completed" to "Undo", "Could not save" to "Retry")) {
                Shot
                    .launch(Variant.EnLight, heightDp = height, widthDp = width, keyboard = true) {
                        ShellScreen(ShellState(Destination.List), {}) {
                            val host = remember { SnackbarHostState() }
                            LaunchedEffect(host) {
                                host.show(message, action)
                            }
                            ListScreen(ListFixtureStates.filtering, {}, host)
                        }
                    }.use { shot ->
                        val keyboardTop = shot.windowHeightPx - shot.dpToPx(SystemBars.KEYBOARD_DP)
                        val button = shot.visibleTextBounds(action)
                        assertTrue("$action $button overlaps keyboard at $keyboardTop px", button.bottom <= keyboardTop)
                    }
            }
        }
    }

    @Test
    fun titleMessagesTransferToTheFilteredPublicListAboveTheKeyboard() {
        for ((width, height) in listOf(411 to 550, 840 to 600)) {
            for ((message, action) in listOf("Moved to Completed" to "Undo", "Could not save" to "Retry")) {
                val titleShown = mutableStateOf(true)
                val host = SnackbarHostState()
                Shot
                    .launch(Variant.EnLight, heightDp = height, widthDp = width, keyboard = true) {
                        LaunchedEffect(host) { host.show(message, action) }
                        ShellScreen(ShellState(Destination.Profile), {}) {
                            if (titleShown.value) {
                                TitleScreen(TitleFixtures.of(FixtureAnime.exactToWatch), {}, host)
                            } else {
                                ListScreen(UserListFixtureStates.filtering, {}, host)
                            }
                        }
                    }.use { shot ->
                        val posted = host.currentSnackbarData
                        assertNotNull(posted)
                        titleShown.value = false
                        shot.frame()
                        val keyboardTop = shot.windowHeightPx - shot.dpToPx(SystemBars.KEYBOARD_DP)
                        val button = shot.visibleTextBounds(action)
                        assertTrue("$action $button overlaps keyboard at $keyboardTop px", button.bottom <= keyboardTop)
                        assertSame(posted, host.currentSnackbarData)
                    }
            }
        }
    }
}
