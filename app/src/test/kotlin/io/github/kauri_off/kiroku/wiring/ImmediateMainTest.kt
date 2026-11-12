package io.github.kauri_off.kiroku.wiring

import androidx.lifecycle.SavedStateHandle
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.edit.EditSheetViewModel
import io.github.kauri_off.kiroku.list.ListContent
import io.github.kauri_off.kiroku.list.ListViewModel
import io.github.kauri_off.kiroku.shell.ShellViewModel
import io.github.kauri_off.kiroku.title.TitleState
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.title.TitleViewModel
import io.github.kauri_off.kiroku.titlesub.DetailsState
import io.github.kauri_off.kiroku.titlesub.DetailsViewModel
import io.github.kauri_off.kiroku.titlesub.RelatedViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// On a device viewModelScope runs in place on Main.immediate: a view model's construction must not depend on order.
@OptIn(ExperimentalCoroutinesApi::class)
class ImmediateMainTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    @Test
    fun `every view model is built, read nothing until shown, and is then shown, on an immediate main dispatcher`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val id = FixtureAnime.exactToWatch.id
            val entries =
                graph.list
                    .observeEntries()
                    .first()
                    .associateBy { it.anime.id }
            val summary = TitleSummary(entries.getValue(id).anime, null, entries, graph.airing.observeAiring().first(), null)
            val shell = ShellViewModel(graph.list)
            val messages = shell.messages
            val list =
                ListViewModel(graph.list, graph.airing, graph.session, graph.clock, graph.titleLanguage, SavedStateHandle(), messages)
            val title =
                TitleViewModel(
                    id,
                    graph.anime,
                    graph.list,
                    graph.airing,
                    graph.session,
                    graph.clock,
                    graph.titleLanguage,
                    SavedStateHandle(),
                    messages,
                    summary,
                )
            val related = RelatedViewModel(id, graph.anime, graph.list, graph.airing, graph.session, graph.titleLanguage, summary)
            val details = DetailsViewModel(id, graph.anime, graph.session, graph.titleLanguage, graph.clock, summary)
            val editor =
                EditSheetViewModel(graph.list, graph.anime, graph.airing, graph.clock, graph.titleLanguage, SavedStateHandle(), messages)
            assertEquals(0, graph.readers)
            assertEquals(0, graph.refreshes)
            assertTrue(title.state.value is TitleState.Shown)

            listOf(list.state, title.state, related.state, details.state).forEach { showing(it) }
            editor.restore()
            editor.open(id)
            advanceUntilIdle()
            assertTrue(list.state.value.content is ListContent.Tabs)
            assertTrue(title.state.value is TitleState.Shown)
            assertTrue(details.state.value is DetailsState.Shown)
            assertNotNull(editor.sheet)
            scope.cancel()
        }
}
