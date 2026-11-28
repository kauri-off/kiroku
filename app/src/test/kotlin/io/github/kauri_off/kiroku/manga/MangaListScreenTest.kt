package io.github.kauri_off.kiroku.manga

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.list.ListPane
import io.github.kauri_off.kiroku.list.ListScreen
import io.github.kauri_off.kiroku.list.ListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `the sort sheet selects chapters read and either direction`() {
        val content = mangaListFixture()
        var controls by mutableStateOf(MangaListControls())
        compose.setContent {
            Screen(MangaListState(content = content, sorts = controls.sorts, sortTab = controls.sortTab)) {
                controls = controls.after(it, content)
            }
        }
        compose.onNodeWithContentDescription("Sort").performClick()
        compose.onNodeWithText("Chapters read").performClick().assertIsSelected()
        compose.onNodeWithText("Most first").assertIsOn()
        compose.onNodeWithText("Fewest first").performClick().assertIsOn()
        assertEquals(TabSort(ListSort.ChaptersRead, SortDirection.Ascending), controls.sorts[mangaEntry.status])
        compose.onNodeWithText("Most first").performClick().assertIsOn()
        assertEquals(TabSort(ListSort.ChaptersRead, SortDirection.Descending), controls.sorts[mangaEntry.status])
    }

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

    @Test
    fun `Back to filtered matches anchors the same title when an earlier match disappeared`() {
        val entries = longList()
        val query = ListState.Typed("Volume")
        val initial = MangaListState(content = mangaListFixture(entries), filter = query, matches = entries)
        var titleOpen by mutableStateOf(false)
        state = initial
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            if (titleOpen) {
                Text("Manga title")
            } else {
                holder.SaveableStateProvider("list") {
                    Screen(state) { if (it is MangaListEvent.Open) titleOpen = true }
                }
            }
        }
        val anchor = scrollToAnchor(entries)
        val top = anchor.getUnclippedBoundsInRoot().top
        anchor.performClick()
        compose.onNodeWithText("Manga title").assertIsDisplayed()
        compose.runOnIdle {
            val remaining = entries.drop(1)
            state = initial.copy(content = mangaListFixture(remaining), matches = remaining)
            titleOpen = false
        }
        assertEquals(top, anchor.getUnclippedBoundsInRoot().top)
        compose.onNodeWithText(entries[5].titles.romaji).assertIsNotDisplayed()
    }

    @Test
    fun `restoring a sorted tab waits through loading and an empty cache before resolving the anchor`() {
        val entries = longList()
        val initial =
            MangaListState(
                content = mangaListFixture(entries),
                sorts = mapOf(mangaEntry.status to TabSort(ListSort.ChaptersRead, SortDirection.Ascending)),
            )
        var recreating = false
        state = initial.copy(content = MangaListContent.Loading)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Screen(if (recreating) state else initial) {} }
        val anchor = scrollToAnchor(entries)
        val top = anchor.getUnclippedBoundsInRoot().top
        recreating = true
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { state = initial.copy(content = mangaListFixture(emptyList())) }
        assertEmptyLibraryDisplayed()
        compose.runOnIdle { state = initial.copy(content = mangaListFixture(entries.drop(1))) }
        assertEquals(top, anchor.getUnclippedBoundsInRoot().top)
        compose.onNodeWithText(entries[5].titles.romaji).assertIsNotDisplayed()
    }

    @Test
    fun `the mounted status viewport survives temporary empty rows`() = mountedViewport(null)

    @Test
    fun `the mounted filter viewport survives temporary empty matches`() = mountedViewport("Volume")

    private fun mountedViewport(query: String?) {
        val entries = longList()
        val initial = MangaListState(content = mangaListFixture(entries), filter = ListState.Typed(query), matches = entries)
        state = initial
        compose.setContent { Screen(state) {} }
        val anchor = scrollToAnchor(entries)
        val top = anchor.getUnclippedBoundsInRoot().top
        compose.runOnIdle { state = initial.copy(content = mangaListFixture(emptyList()), matches = emptyList()) }
        if (query == null) assertEmptyLibraryDisplayed() else compose.onNodeWithText("No titles match the filter").assertIsDisplayed()
        compose.runOnIdle { state = initial.copy(content = mangaListFixture(entries.drop(1)), matches = entries.drop(1)) }
        assertEquals(top, anchor.getUnclippedBoundsInRoot().top)
    }

    private fun longList() = (1L..24).map { id -> mangaEntry.copy(id = MangaId(id), titles = Titles("Volume $id"), posterUrl = null) }

    private fun scrollToAnchor(entries: List<MangaListEntry>): SemanticsNodeInteraction {
        val title = entries[6].titles.romaji
        val anchor = compose.onNodeWithText(title)
        scroller(entries.first().titles.romaji).performScrollToIndex(6)
        val unscrolled = anchor.getUnclippedBoundsInRoot().top
        scroller(title).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 21f) }
        assertNotEquals(unscrolled, anchor.getUnclippedBoundsInRoot().top)
        return anchor
    }

    private fun scroller(title: String) =
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(hasText(title)),
        )

    private fun assertEmptyLibraryDisplayed() {
        val messages = compose.onAllNodesWithText("Your manga list is empty")
        assertEquals(1, messages.fetchSemanticsNodes().indices.count { messages[it].isDisplayed() })
    }

    private fun show(
        content: MangaListContent.Ready,
        tab: MangaReadingStatus = mangaEntry.status,
    ) {
        state = MangaListState(content = content, tab = tab)
        compose.setContent { Screen(state) { if (it is MangaListEvent.SelectTab) state = state.copy(tab = it.status) } }
    }

    @Composable
    private fun Screen(
        state: MangaListState,
        onEvent: (MangaListEvent) -> Unit,
    ) {
        KirokuTheme(dark = false, dynamicColor = false) {
            ListScreen(ListPane.Manga(state, onEvent), remember { SnackbarHostState() }, onMode = {})
        }
    }
}
