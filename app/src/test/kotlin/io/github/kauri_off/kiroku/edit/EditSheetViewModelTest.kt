package io.github.kauri_off.kiroku.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.PartialDate
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class EditSheetViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val anime = FixtureAnime.exactToWatch

    private class Subject(
        val graph: TestGraph,
        val editor: EditSheetViewModel,
    ) {
        val sheet: EditSheetState get() = editor.sheet!!

        suspend fun values(anime: Anime) =
            graph.list
                .observeEntry(anime.id)
                .first()
                ?.values

        fun type(vararg texts: String) = texts.forEach { editor.onEvent(EditSheetEvent.WatchedTyped(it)) }
    }

    private fun test(
        saved: SavedStateHandle = SavedStateHandle(),
        open: Anime? = FixtureAnime.exactToWatch,
        slowWrites: Boolean = false,
        body: suspend TestScope.(Subject) -> Unit,
    ) = runTest(dispatcher) {
        val scope = foregroundScope()
        val graph = TestGraph.over(scope, slowWrites)
        val editor =
            EditSheetViewModel(
                graph.list,
                graph.anime,
                graph.airing,
                graph.clock,
                graph.titleLanguage,
                saved,
                ShellMessages(scope, graph.list),
            )
        val subject = Subject(graph, editor)
        editor.restore()
        open?.let { editor.open(it.id) }
        advanceUntilIdle()
        body(subject)
        scope.cancel()
    }

    @Test
    fun `open builds the sheet from the stored entry`() =
        test { s ->
            val entry =
                s.graph.list
                    .observeEntry(anime.id)
                    .first()!!
            assertEquals(
                EditSheetState.of(
                    anime,
                    s.graph.demo.fixtures.airing[anime.id],
                    entry.values,
                    s.graph.titleLanguage,
                    LocalDate.now(s.graph.clock),
                ),
                s.sheet,
            )
        }

    @Test
    fun `every keystroke is applied in onEvent itself, before any coroutine runs`() =
        test { s ->
            s.type("2", "24")
            assertEquals("24", s.sheet.typed)
            assertEquals(24, s.sheet.draft.watchedEpisodes)
            assertEquals(ListStatus.Completed, s.sheet.draft.status)
            s.type("2", "")
            assertEquals(12, s.sheet.draft.watchedEpisodes)
            assertEquals(ListStatus.Watching, s.sheet.draft.status)
            s.editor.onEvent(EditSheetEvent.TypingEnded)
            assertNull(s.sheet.typed)
        }

    @Test
    fun `nothing is written before Save, and Save writes exactly what the sheet shows`() =
        test { s ->
            s.editor.onEvent(EditSheetEvent.StatusChosen(ListStatus.OnHold))
            s.editor.onEvent(EditSheetEvent.WatchedChanged(13))
            s.editor.onEvent(EditSheetEvent.ScoreChanged(9))
            s.editor.onEvent(EditSheetEvent.DateOpened(DateField.Finish))
            assertEquals(EditDialog.PickDate(DateField.Finish), s.sheet.dialog)
            s.editor.onEvent(EditSheetEvent.DatePicked(DateField.Finish, LocalDate.of(2026, 1, 2)))
            s.editor.onEvent(EditSheetEvent.DateCleared(DateField.Start))
            s.editor.onEvent(EditSheetEvent.DateOpened(DateField.Start))
            s.editor.onEvent(EditSheetEvent.DialogDismissed)
            assertNull(s.sheet.dialog)
            advanceUntilIdle()
            assertEquals(12, s.values(anime)?.watchedEpisodes)
            val draft = s.sheet.draft
            assertEquals(ListStatus.OnHold, draft.status)
            assertEquals(9, draft.score)
            assertNull(draft.startDate)
            assertEquals(PartialDate.Day(LocalDate.of(2026, 1, 2)), draft.finishDate)
            s.editor.onEvent(EditSheetEvent.SaveClicked)
            assertTrue(s.sheet.closing)
            advanceUntilIdle()
            assertEquals(draft, s.values(anime))
            s.editor.onEvent(EditSheetEvent.Dismissed)
            assertNull(s.editor.sheet)
        }

    @Test
    fun `Save is written although the sheet's screen is closed before the write is applied`() =
        test(slowWrites = true) { s ->
            s.editor.onEvent(EditSheetEvent.ScoreChanged(9))
            s.editor.onEvent(EditSheetEvent.SaveClicked)
            s.editor.viewModelScope.cancel()
            advanceUntilIdle()
            assertEquals(9, s.values(anime)?.score)
        }

    @Test
    fun `Save is refused while the count field is empty`() =
        test { s ->
            s.type("")
            s.editor.onEvent(EditSheetEvent.SaveClicked)
            assertEquals(false, s.sheet.closing)
        }

    @Test
    fun `Remove asks first and then deletes the entry`() =
        test { s ->
            s.editor.onEvent(EditSheetEvent.RemoveClicked)
            assertEquals(EditDialog.ConfirmRemove, s.sheet.dialog)
            advanceUntilIdle()
            assertNotNull(s.values(anime))
            s.editor.onEvent(EditSheetEvent.RemoveConfirmed)
            advanceUntilIdle()
            assertNull(s.values(anime))
        }

    @Test
    fun `dismissing with changes asks, and Discard closes without writing`() =
        test { s ->
            s.editor.onEvent(EditSheetEvent.ScoreChanged(3))
            s.editor.onEvent(EditSheetEvent.DismissAsked)
            s.editor.onEvent(EditSheetEvent.DismissAsked)
            assertEquals(EditDialog.ConfirmDiscard, s.sheet.dialog)
            s.editor.onEvent(EditSheetEvent.DiscardConfirmed)
            assertTrue(s.sheet.closing)
            s.editor.onEvent(EditSheetEvent.Dismissed)
            advanceUntilIdle()
            assertNull(s.editor.sheet)
            assertEquals(8, s.values(anime)?.score)
        }

    @Test
    fun `a sheet closed and opened again starts from the stored entry`() =
        test { s ->
            s.editor.onEvent(EditSheetEvent.ScoreChanged(3))
            s.editor.onEvent(EditSheetEvent.DiscardConfirmed)
            s.editor.onEvent(EditSheetEvent.Dismissed)
            s.editor.open(anime.id)
            advanceUntilIdle()
            assertEquals(8, s.sheet.draft.score)
            assertEquals(false, s.sheet.closing)
        }

    @Test
    fun `an open sheet, a typed count and a dialog survive process death`() {
        val saved = SavedStateHandle()
        lateinit var before: EditSheetState
        test(saved = saved) { s ->
            s.editor.onEvent(EditSheetEvent.DatePicked(DateField.Start, LocalDate.of(2025, 9, 1)))
            s.type("2", "26")
            s.editor.onEvent(EditSheetEvent.DateOpened(DateField.Finish))
            before = s.sheet
        }
        test(saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }), open = null) { s ->
            assertEquals(before, s.sheet)
            assertEquals("26", s.sheet.typed)
            assertEquals(26, s.sheet.draft.watchedEpisodes)
            assertEquals(EditDialog.PickDate(DateField.Finish), s.sheet.dialog)
            s.type("")
            assertEquals(12, s.sheet.draft.watchedEpisodes)
        }
    }

    @Test
    fun `a sheet closed before process death does not come back`() {
        val saved = SavedStateHandle()
        test(saved = saved) { s -> s.editor.onEvent(EditSheetEvent.Dismissed) }
        test(saved = saved, open = null) { s -> assertNull(s.editor.sheet) }
    }
}
