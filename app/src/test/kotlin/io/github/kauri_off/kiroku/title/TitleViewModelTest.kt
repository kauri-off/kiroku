package io.github.kauri_off.kiroku.title

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureDetails
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
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
        val messages: ShellMessages,
        val title: TitleViewModel,
    ) {
        val state: TitleState get() = title.state.value
        val message: ShellMessage? get() = messages.current?.message

        fun tapAction() = messages.ended(messages.current!!, actionPerformed = true)

        val shown: TitleState.Shown get() = state as TitleState.Shown

        fun watched() = shown.page.entry?.watchedEpisodes

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
        lateObservers: Boolean = false,
        slowWrites: Boolean = false,
        body: suspend TestScope.(Subject) -> Unit,
    ) = runTest(dispatcher) {
        val scope = foregroundScope()
        val graph = TestGraph(Demo(Fixtures.reference, scope), lateObservers, slowWrites).apply(before)
        val messages = ShellMessages(scope, graph.list)
        val subject = Subject(graph, messages, TitleViewModel(graph, id, saved, messages))
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
            assertNull(s.message)
        }

    // Three quick taps on the stepper's plus before the page shows the first: each counts (W3, section 7.3).
    private fun stepperBurst(lateObservers: Boolean) =
        test(lateObservers = lateObservers) { s ->
            repeat(3) { s.title.onEvent(TitleEvent.StepWatched(1)) }
            advanceUntilIdle()
            assertEquals(15, s.values()?.watchedEpisodes)
            s.title.onEvent(TitleEvent.StepWatched(-1))
            s.title.onEvent(TitleEvent.StepWatched(-1))
            advanceUntilIdle()
            assertEquals(13, s.values()?.watchedEpisodes)
        }

    @Test
    fun `three quick stepper taps all count`() = stepperBurst(lateObservers = false)

    @Test
    fun `three quick stepper taps all count, with late observers`() = stepperBurst(lateObservers = true)

    @Test
    fun `W3 stepping up to the total completes the title and Undo takes back only the completing step`() =
        test(lateObservers = true) { s ->
            s.title.onEvent(TitleEvent.SetWatched(22))
            advanceUntilIdle()
            val before = s.values()
            s.title.onEvent(TitleEvent.StepWatched(1))
            s.title.onEvent(TitleEvent.StepWatched(1))
            advanceUntilIdle()
            assertEquals(ListStatus.Completed, s.values()?.status)
            assertEquals(ShellMessage.Text.MovedToCompleted, s.message?.text)
            s.tapAction()
            advanceUntilIdle()
            assertEquals(before?.copy(watchedEpisodes = 23), s.values())
        }

    @Test
    fun `a typed count keeps a score set since the page last showed the entry`() =
        test(lateObservers = true) { s ->
            val anime = FixtureAnime.exactToWatch
            s.graph.list.write(ListWrite.Save(anime, s.values()!!.copy(score = 3)))
            s.title.onEvent(TitleEvent.SetWatched(14))
            advanceUntilIdle()
            assertEquals(3, s.values()?.score)
            assertEquals(14, s.values()?.watchedEpisodes)
        }

    @Test
    fun `SetWatched to the total completes the title, and the snackbar's Undo restores every value`() =
        test { s ->
            val before = s.values()
            s.title.onEvent(TitleEvent.SetWatched(24))
            advanceUntilIdle()
            assertEquals(ListStatus.Completed, s.values()?.status)
            assertEquals(ShellMessage.Text.MovedToCompleted, s.message?.text)
            s.tapAction()
            advanceUntilIdle()
            assertEquals(before, s.values())
            assertNull(s.message)
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
    fun `the Undo outlives the page`() =
        test { s ->
            val before = s.values()
            s.title.onEvent(TitleEvent.SetWatched(24))
            advanceUntilIdle()
            s.title.viewModelScope.cancel()
            s.tapAction()
            advanceUntilIdle()
            assertEquals(before, s.values())
        }

    @Test
    fun `a completing step is written and says so although its page is closed before the write is applied`() =
        test(slowWrites = true) { s ->
            s.title.onEvent(TitleEvent.SetWatched(24))
            s.title.viewModelScope.cancel()
            advanceUntilIdle()
            assertEquals(ListStatus.Completed, s.values()?.status)
            assertEquals(ShellMessage.Text.MovedToCompleted, s.message?.text)
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
            assertEquals(
                ShellMessage(ShellMessage.Text.AddedToPlanned, ShellMessage.Action.Change(FixtureAnime.notOnListAiring.id)),
                s.message,
            )
            s.tapAction()
            assertEquals(FixtureAnime.notOnListAiring.id, s.messages.editRequest)
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
    fun `a list that cannot be read fails the page, and Retry shows it and follows the list again`() =
        test(before = { listUnreadable = true }) { s ->
            assertEquals(DataError.StorageError, (s.state as TitleState.Failed).error)

            s.graph.listUnreadable = false
            s.title.onEvent(TitleEvent.Retry)
            advanceUntilIdle()
            assertEquals(12, s.watched())
            s.graph.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            advanceUntilIdle()
            assertEquals(13, s.watched())
        }

    @Test
    fun `a failed refresh with the details shown keeps the page and says so`() =
        test { s ->
            s.graph.demo.server.offline.value = true
            s.title.onEvent(TitleEvent.Retry)
            advanceUntilIdle()
            assertEquals(FixtureDetails.full, s.shown.page.details)
            assertEquals(ShellMessage(ShellMessage.Text.RefreshFailed(DataError.NoConnection)), s.message)
        }

    @Test
    fun `the session is unknown, not signed out, until it is read`() = test(settle = false) { s -> assertNull(s.state.session) }

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
