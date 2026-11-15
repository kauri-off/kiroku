package io.github.kauri_off.kiroku.title

import android.content.ClipboardManager
import android.widget.Magnifier
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import kotlinx.coroutines.awaitCancellation
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

// The title and the synopsis offer the platform's text selection, which leaves the synopsis' buttons and the scroll as they are.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi", shadows = [TitleSelectionTest.NoMagnifier::class])
class TitleSelectionTest {
    // Robolectric gives the platform's magnifier no surface to draw on, so it is not drawn here.
    @Implements(Magnifier::class)
    class NoMagnifier {
        @Implementation
        fun show(
            sourceCenterX: Float,
            sourceCenterY: Float,
            magnifierCenterX: Float,
            magnifierCenterY: Float,
        ) = Unit

        @Implementation
        fun dismiss() = Unit

        @Implementation
        fun update() = Unit
    }

    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    // The selection's toolbar, as the platform's floating one is given it: what it offers, while it is shown.
    private class Toolbar : TextContextMenuProvider {
        private var shown: TextContextMenuDataProvider? = null

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            shown = dataProvider
            try {
                awaitCancellation()
            } finally {
                // A menu shown again may start before the one it replaces has ended.
                if (shown === dataProvider) shown = null
            }
        }

        fun item(key: Any): TextContextMenuItem? =
            shown
                ?.data()
                ?.components
                ?.filterIsInstance<TextContextMenuItem>()
                ?.firstOrNull { it.key == key }
    }

    private val session =
        object : TextContextMenuSession {
            override fun close() = Unit
        }

    private val toolbar = Toolbar()
    private var state by mutableStateOf<TitleState>(TitleFixtures.of(FixtureAnime.longTitle))

    private fun show(shown: TitleState.Shown) {
        state = shown
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                    TitleInShell(state, onEvent = { event ->
                        (state as? TitleState.Shown)?.let { state = it.copy(moment = it.moment.after(event)) }
                    })
                }
            }
        }
        compose.waitForIdle()
    }

    // Selects all of the text under a long press and copies it, as the toolbar's two items do. The toolbar shows
    // once the platform has classified the selection, off the main thread.
    private fun copyAll(text: String): String? {
        compose.onNodeWithText(text, substring = true).performTouchInput { longClick(center) }
        offered(TextContextMenuKeys.SelectAllKey).onClick(session)
        offered(TextContextMenuKeys.CopyKey).onClick(session)
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        compose.waitUntil(WAIT_MILLIS) { clipboard.hasPrimaryClip() }
        return clipboard.primaryClip
            ?.getItemAt(0)
            ?.text
            ?.toString()
    }

    private fun offered(key: Any): TextContextMenuItem {
        compose.waitUntil(WAIT_MILLIS) { toolbar.item(key) != null }
        return checkNotNull(toolbar.item(key))
    }

    // The drawn title may break after its hyphens; a copy is the title as MAL writes it.
    @Test
    fun theTitleOffersTheSelectionAndCopiesAsWritten() {
        val shown = TitleFixtures.of(FixtureAnime.longTitle)
        show(shown)
        assertTrue(shown.page.title.contains('-'))
        assertEquals(shown.page.title, copyAll(shown.page.title))
    }

    // A window tall enough to show the synopsis without a scroll, and short enough for the page to scroll.
    @Test
    @Config(qualifiers = "en-w411dp-h1400dp-xhdpi")
    fun theSynopsisOffersTheSelectionAndStillExpandsCollapsesAndScrolls() {
        val shown = TitleFixtures.of(FixtureAnime.exactToWatch)
        val synopsis = checkNotNull(shown.page.details?.synopsis)
        val start = synopsis.take(SYNOPSIS_START)
        show(shown)
        assertEquals(synopsis, copyAll(start))

        compose.onNodeWithText("More").performClick()
        compose.waitForIdle()
        assertTrue((state as TitleState.Shown).moment.synopsisExpanded)
        compose.onNodeWithText("Less").performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse((state as TitleState.Shown).moment.synopsisExpanded)

        val top = compose.onNodeWithText(start, substring = true).getUnclippedBoundsInRoot().top
        compose.onNodeWithText(start, substring = true).performTouchInput { swipeUp() }
        compose.waitForIdle()
        val moved = compose.onNodeWithText(start, substring = true).getUnclippedBoundsInRoot().top
        assertTrue("a drag on the synopsis scrolls the page: $top, then $moved", moved < top)
    }

    private companion object {
        const val SYNOPSIS_START = 30

        const val WAIT_MILLIS = 10_000L
    }
}
