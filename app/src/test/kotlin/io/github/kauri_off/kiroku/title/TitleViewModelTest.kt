package io.github.kauri_off.kiroku.title

import androidx.lifecycle.SavedStateHandle
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TitleViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private class Subject(
        val graph: TestGraph,
        val title: TitleViewModel,
    ) {
        val state: TitleState get() = title.state.value
        val shown: TitleState.Shown get() = state as TitleState.Shown

        suspend fun values() =
            graph.list
                .observeEntry(shown.page.anime.id)
                .first()
                ?.values
    }

    private fun test(
        id: AnimeId = FixtureAnime.exactToWatch.id,
        saved: SavedStateHandle = SavedStateHandle(),
        before: TestGraph.() -> Unit = {},
        settle: Boolean = true,
        body: suspend TestScope.(Subject) -> Unit,
    ) = runTest(dispatcher) {
        val scope = foregroundScope()
        val graph = TestGraph.over(scope).apply(before)
        val subject = Subject(graph, TitleViewModel(graph, id, saved))
        if (settle) advanceUntilIdle()
        body(subject)
        scope.cancel()
    }

    @Test
    fun `the page shows the stored title at once and the details when they arrive`() =
        test(settle = false) { s ->
            runCurrent()
            assertEquals(FixtureAnime.exactToWatch, s.shown.page.anime)
            advanceUntilIdle()
            assertEquals(FixtureDetails.full, s.shown.page.details)
            assertEquals(
                12,
                s.shown.page.entry
                    ?.watchedEpisodes,
            )
            assertTrue(
                s.shown.page.related
                    .isNotEmpty(),
            )
        }

    @Test
    fun `SetWatched writes the count at once and shows no snackbar below the total`() =
        test { s ->
            s.title.onEvent(TitleEvent.SetWatched(13))
            advanceUntilIdle()
            assertEquals(13, s.values()?.watchedEpisodes)
            assertEquals(
                13,
                s.shown.page.entry
                    ?.watchedEpisodes,
            )
            assertNull(s.shown.moment.snackbar)
        }

    @Test
    fun `SetWatched to the total completes the title, and the snackbar's Undo restores every value`() =
        test { s ->
            val before = s.values()
            s.title.onEvent(TitleEvent.SetWatched(24))
            advanceUntilIdle()
            assertEquals(ListStatus.Completed, s.values()?.status)
            assertEquals(TitleSnackbar.MovedToCompleted, s.shown.moment.snackbar)
            s.title.onEvent(TitleEvent.SnackbarAction)
            advanceUntilIdle()
            assertEquals(before, s.values())
            assertNull(s.shown.moment.snackbar)
        }

    @Test
    fun `W9 SetWatched above the total is accepted and completes the title`() =
        test { s ->
            s.title.onEvent(TitleEvent.SetWatched(30))
            advanceUntilIdle()
            assertEquals(30, s.values()?.watchedEpisodes)
            assertEquals(ListStatus.Completed, s.values()?.status)
        }

    @Test
    fun `SnackbarDismissed clears the snackbar and the Undo with it`() =
        test { s ->
            s.title.onEvent(TitleEvent.SetWatched(24))
            advanceUntilIdle()
            s.title.onEvent(TitleEvent.SnackbarDismissed)
            assertNull(s.shown.moment.snackbar)
            s.title.onEvent(TitleEvent.SnackbarAction)
            advanceUntilIdle()
            assertEquals(ListStatus.Completed, s.values()?.status)
        }

    @Test
    fun `AddToList adds the title as Planned and says so`() =
        test(id = FixtureAnime.notOnListAiring.id) { s ->
            assertNull(s.shown.page.entry)
            s.title.onEvent(TitleEvent.AddToList)
            advanceUntilIdle()
            assertEquals(ListStatus.Planned, s.values()?.status)
            assertEquals(
                ListStatus.Planned,
                s.shown.page.entry
                    ?.status,
            )
            assertEquals(TitleSnackbar.AddedToPlanned, s.shown.moment.snackbar)
            s.title.onEvent(TitleEvent.SnackbarAction)
            assertNull(s.shown.moment.snackbar)
            assertEquals(ListStatus.Planned, s.values()?.status)
        }

    @Test
    fun `the synopsis expands and collapses, and survives a rebuilt page and process death`() {
        val saved = SavedStateHandle()
        test(saved = saved) { s ->
            s.title.onEvent(TitleEvent.ExpandSynopsis)
            assertTrue(s.shown.moment.synopsisExpanded)
            s.title.onEvent(TitleEvent.SetWatched(13))
            advanceUntilIdle()
            assertTrue(s.shown.moment.synopsisExpanded)
        }
        test(saved = SavedStateHandle(mapOf("synopsisExpanded" to saved.get<Boolean>("synopsisExpanded")))) { s ->
            assertTrue(s.shown.moment.synopsisExpanded)
            s.title.onEvent(TitleEvent.CollapseSynopsis)
            assertEquals(false, s.shown.moment.synopsisExpanded)
        }
    }

    @Test
    fun `a failed load shows the error, and Retry loads the page`() =
        test(before = { demo.server.offline.value = true }) { s ->
            assertEquals(DataError.NoConnection, (s.state as TitleState.Failed).error)
            s.graph.demo.server.offline.value = false
            s.title.onEvent(TitleEvent.Retry)
            advanceUntilIdle()
            assertEquals(FixtureDetails.full, s.shown.page.details)
        }

    @Test
    fun `an unknown title is not found`() =
        test(id = AnimeId(999_999_999)) { s ->
            assertEquals(DataError.NotFound, (s.state as TitleState.Failed).error)
        }

    @Test
    fun `the session reaches the page's state`() =
        test { s ->
            assertEquals(Session.SignedIn("kiroku_demo"), s.state.session)
            s.graph.demo.server.sessionExpired.value = true
            advanceUntilIdle()
            assertTrue(s.state.sessionExpired)
        }

    @Test
    fun `the events the route handles change nothing here`() =
        test { s ->
            val before = s.state
            listOf(
                TitleEvent.Back,
                TitleEvent.OpenOnMal,
                TitleEvent.Share,
                TitleEvent.Edit,
                TitleEvent.OpenRelated(FixtureAnime.prequel.id),
                TitleEvent.SeeAllRelated,
                TitleEvent.OpenDetails,
                TitleEvent.OpenRecommendations,
                TitleEvent.SignIn,
            ).forEach(s.title::onEvent)
            advanceUntilIdle()
            assertEquals(before, s.state)
        }
}
