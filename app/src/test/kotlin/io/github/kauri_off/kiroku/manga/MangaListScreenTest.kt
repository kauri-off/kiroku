package io.github.kauri_off.kiroku.manga

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.list.ListPane
import io.github.kauri_off.kiroku.list.ListScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class MangaListScreenTest {
    private val compose = composeRule()
    private var state by mutableStateOf(MangaListState())

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    fun `a swipe selects the next status and tabs show only their own rows`() {
        show(mangaListFixture())
        compose.onNodeWithText("Reading").assertIsSelected()
        val reading = compose.onNodeWithText(mangaEntry.titles.romaji)
        val planned = compose.onNodeWithText("Rain at the Edge of Town")
        reading.assertIsDisplayed()
        planned.assertIsNotDisplayed()

        reading.performTouchInput { swipeLeft() }
        compose.onNodeWithText("Planned").assertIsSelected()
        planned.assertIsDisplayed()
        reading.assertIsNotDisplayed()

        compose.onNodeWithText("Read").performClick().assertIsSelected()
        compose.onNodeWithText("The Lantern District").assertIsDisplayed()
        compose.onNodeWithText("On hold").performClick().assertIsSelected()
        compose.onNodeWithText("Between the Tides").assertIsDisplayed()
        compose.onNodeWithText("Dropped").performClick().assertIsSelected()
        compose.onNodeWithText("No titles in Dropped").assertIsDisplayed()
    }

    @Test
    fun `an empty library keeps its tabs and shows its own empty message`() {
        show(mangaListFixture(emptyList()))
        assertEmptyLibraryDisplayed()
        compose.onNodeWithText("Read").performClick().assertIsSelected()
        assertEmptyLibraryDisplayed()
    }

    @Test
    fun `an unfamiliar status is reachable and removing its last row leaves a valid page`() {
        val unknown = MangaReadingStatus("rereading")
        val entry = mangaEntry.copy(id = MangaId(99), titles = Titles("A second reading"), status = unknown)
        show(mangaListFixture(listOf(mangaEntry, entry)), unknown)
        compose.onNodeWithText("Rereading").assertIsSelected()
        compose.onNodeWithText("A second reading").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(content = mangaListFixture(listOf(mangaEntry))) }
        compose.onNodeWithText("Reading").assertIsSelected()
        compose.onNodeWithText(mangaEntry.titles.romaji).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(content = mangaListFixture(listOf(mangaEntry, entry))) }
        compose.onNodeWithText("Reading").assertIsSelected()
    }

    @Test
    fun `a status the formatter would erase keeps its label`() {
        val punctuation = MangaReadingStatus("__")
        show(mangaListFixture(listOf(mangaEntry.copy(status = punctuation))), punctuation)
        compose.onNodeWithText("__").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithText(mangaEntry.titles.romaji).assertIsDisplayed()
    }

    private fun assertEmptyLibraryDisplayed() {
        val messages = compose.onAllNodesWithText("Your manga list is empty")
        assertEquals(1, messages.fetchSemanticsNodes().indices.count { messages[it].isDisplayed() })
    }

    private fun show(
        content: MangaListContent.Ready,
        tab: MangaReadingStatus = mangaEntry.status,
    ) {
        state = MangaListState(content = content, tab = tab)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                ListScreen(
                    ListPane.Manga(state) { if (it is MangaListEvent.SelectTab) state = state.copy(tab = it.status) },
                    remember { SnackbarHostState() },
                    onMode = {},
                )
            }
        }
    }
}
