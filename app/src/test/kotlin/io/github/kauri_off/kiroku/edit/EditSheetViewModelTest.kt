package io.github.kauri_off.kiroku.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListField
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.MovingClock
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

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
        before: suspend (TestGraph) -> Unit = {},
        body: suspend TestScope.(Subject) -> Unit,
    ) = runTest(dispatcher) {
        val scope = foregroundScope()
        val graph = TestGraph.over(scope, slowWrites)
        before(graph)
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
    fun `opening the sheet asks the source of episode times nothing, however stale the stored data`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val clock = MovingClock(Fixtures.reference.now, FixtureTime.zone)
            val graph = TestGraph(Demo(Fixtures.reference, scope, clock = clock))
            clock.instant = clock.instant.plus(Duration.ofDays(2))
            val unlisted = FixtureAnime.notOnListAiring
            for (opened in listOf(anime, unlisted)) {
                val editor =
                    EditSheetViewModel(
                        graph.list,
                        graph.anime,
                        graph.airing,
                        graph.clock,
                        graph.titleLanguage,
                        SavedStateHandle(),
                        ShellMessages(scope, graph.list),
                    )
                editor.open(opened.id, known = opened)
                advanceUntilIdle()
                assertEquals(opened.malEpisodes, editor.sheet!!.total)
            }
            assertEquals(emptyList<Set<AnimeId>>(), graph.demo.airingSource.requests)
            scope.cancel()
        }

    @Test
    fun `a sheet whose airing data cannot be read opens with MAL's total`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope, false)
            val unread =
                object : AiringRepository by graph.airing {
                    override fun observeAiring(): Flow<Map<AnimeId, Airing>> = flow { throw DataException(DataError.StorageError) }
                }
            val editor =
                EditSheetViewModel(
                    graph.list,
                    graph.anime,
                    unread,
                    graph.clock,
                    graph.titleLanguage,
                    SavedStateHandle(),
                    ShellMessages(scope, graph.list),
                )
            editor.open(anime.id)
            advanceUntilIdle()

            assertEquals(anime.malEpisodes, editor.sheet!!.total)
            assertEquals(12, editor.sheet!!.draft.watchedEpisodes)
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
                    ZonedDateTime.now(s.graph.clock),
                ),
                s.sheet,
            )
        }

    @Test
    fun `a title neither on the list nor in the store opens from what the caller knows of it, as a new entry`() =
        test(open = null) { s ->
            val unknown = FixtureAnime.notOnListAiring.copy(id = AnimeId(UNKNOWN_ID))
            s.editor.open(unknown.id)
            advanceUntilIdle()
            assertNull(s.editor.sheet)
            s.editor.open(unknown.id, unknown)
            advanceUntilIdle()
            assertTrue(s.sheet.isNew)
            assertEquals(EditSheetState.newEntry, s.sheet.draft)
            s.editor.onEvent(EditSheetEvent.SaveClicked)
            advanceUntilIdle()
            assertEquals(EditSheetState.newEntry, s.values(unknown))
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
    fun `Save sets only the values the sheet changed, over a change made elsewhere while it was open`() =
        test { s ->
            s.graph.list.write(ListWrite.MarkNextWatched(anime))
            advanceUntilIdle()
            assertEquals(13, s.values(anime)?.watchedEpisodes)
            assertEquals(12, s.sheet.draft.watchedEpisodes)

            s.editor.onEvent(EditSheetEvent.ScoreChanged(9))
            s.editor.onEvent(EditSheetEvent.SaveClicked)
            advanceUntilIdle()
            assertEquals(13, s.values(anime)?.watchedEpisodes)
            assertEquals(9, s.values(anime)?.score)
        }

    private fun markedThirteenth(): suspend (TestGraph) -> Unit =
        {
            assertEquals(Outcome.Success(Unit), it.list.send(ListWrite.MarkWatched(anime, 13)))
        }

    // Section 7.3: "Mark watched" from a notification while the sheet is open stays when the sheet leaves the count alone.
    @Test
    fun `a sheet opened before a send and saved after it with the score alone leaves the send's episode`() =
        test(before = markedThirteenth()) { s ->
            assertEquals(13, s.sheet.draft.watchedEpisodes)
            assertEquals(Outcome.Success(Unit), s.graph.list.send(ListWrite.MarkWatched(anime, 14)))

            s.editor.onEvent(EditSheetEvent.ScoreChanged(9))
            s.editor.onEvent(EditSheetEvent.SaveClicked)
            advanceUntilIdle()
            val server =
                s.graph.demo.server
                    .entry(anime.id)
                    ?.values
            assertEquals(14, server?.watchedEpisodes)
            assertEquals(9, server?.score)
            assertEquals(server, s.values(anime))
        }

    // W1: a count the sheet changed is the one shown last.
    @Test
    fun `a sheet opened before a send and saved after it with the count changed leaves the sheet's count`() =
        test(before = markedThirteenth()) { s ->
            assertEquals(Outcome.Success(Unit), s.graph.list.send(ListWrite.MarkWatched(anime, 14)))

            s.editor.onEvent(EditSheetEvent.WatchedChanged(15))
            s.editor.onEvent(EditSheetEvent.SaveClicked)
            advanceUntilIdle()
            assertEquals(
                15,
                s.graph.demo.server
                    .entry(anime.id)
                    ?.values
                    ?.watchedEpisodes,
            )
            assertEquals(15, s.values(anime)?.watchedEpisodes)
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

    // The values the sheet opened with come back with it, so Save sends what the user changed, not what moved since.
    @Test
    fun `a sheet restored after process death saves only what the user changed in it, over a website edit synced since`() {
        val saved = SavedStateHandle()
        test(saved = saved) { s -> s.editor.onEvent(EditSheetEvent.ScoreChanged(9)) }
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        test(
            saved = restored,
            open = null,
            before = { graph ->
                val website = checkNotNull(graph.demo.server.entry(anime.id)).values.copy(watchedEpisodes = 15)
                graph.demo.server.editOnWebsite(anime, website)
                assertEquals(Outcome.Success(Unit), graph.list.sync())
            },
        ) { s ->
            assertEquals(12, s.sheet.draft.watchedEpisodes)
            assertEquals(15, s.values(anime)?.watchedEpisodes)
            s.editor.onEvent(EditSheetEvent.SaveClicked)
            advanceUntilIdle()
            assertEquals(
                setOf(ListField.Score),
                s.graph.demo.server.requests
                    .single()
                    .values
                    ?.fields,
            )
            assertEquals(
                15,
                s.graph.demo.server
                    .entry(anime.id)
                    ?.values
                    ?.watchedEpisodes,
            )
            assertEquals(
                9,
                s.graph.demo.server
                    .entry(anime.id)
                    ?.values
                    ?.score,
            )
            assertEquals(15, s.values(anime)?.watchedEpisodes)
        }
    }

    // The website added the title while the sheet was open: the add sets what the user set, and keeps the rest.
    @Test
    fun `adding a title the website put on the list meanwhile sets its status and the values set in the sheet alone`() =
        test(open = null) { s ->
            val added = FixtureAnime.notOnListAiring
            s.editor.open(added.id)
            advanceUntilIdle()
            assertTrue(s.sheet.isNew)
            s.editor.onEvent(EditSheetEvent.ScoreChanged(9))
            val website = ListValues(ListStatus.Watching, 5, score = 7, startDate = null, finishDate = null)
            s.graph.demo.server
                .editOnWebsite(added, website)
            assertEquals(Outcome.Success(Unit), s.graph.list.sync())
            assertEquals(website, s.values(added))

            s.editor.onEvent(EditSheetEvent.SaveClicked)
            advanceUntilIdle()
            assertEquals(
                setOf(ListField.Status, ListField.Score),
                s.graph.demo.server.requests
                    .single()
                    .values
                    ?.fields,
            )
            val saved = website.copy(status = ListStatus.Planned, score = 9)
            assertEquals(
                saved,
                s.graph.demo.server
                    .entry(added.id)
                    ?.values,
            )
            assertEquals(saved, s.values(added))
        }

    @Test
    fun `a sheet closed before process death does not come back`() {
        val saved = SavedStateHandle()
        test(saved = saved) { s -> s.editor.onEvent(EditSheetEvent.Dismissed) }
        test(saved = saved, open = null) { s -> assertNull(s.editor.sheet) }
    }

    private companion object {
        const val UNKNOWN_ID = 999_999_999L
    }
}
