package io.github.kauri_off.kiroku.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListNumber
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListEditResult
import io.github.kauri_off.kiroku.core.repository.MangaListEditor
import io.github.kauri_off.kiroku.edit.MangaEditSheetEvent
import io.github.kauri_off.kiroku.edit.MangaEditSubmission
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MangaEditingTest {
    private val owner = SignInId("first")
    private val next = SignInId("second")
    private val entry = mangaEntry.copy(score = 8)

    private class Subject(
        val editing: MangaEditing,
        val messages: ShellMessages,
    ) {
        val open get() = checkNotNull(editing.state.value.open)

        fun event(event: MangaEditSheetEvent) = editing.event(open, event)

        fun save() = event(MangaEditSheetEvent.Save(checkNotNull(open.form.saveChanges)))
    }

    private data class Sent(
        val owner: SignInId,
        val changes: MangaListEdit,
        val retryFrom: MangaListEntry?,
    )

    private inner class Backend(
        baseline: MangaListEntry = entry,
    ) : MangaListEditor {
        val cache = MutableStateFlow(snapshot(owner, baseline))
        val sessions = MutableStateFlow<Session>(Session.SignedIn(owner))
        var answer = CompletableDeferred<MangaListEditResult>()
        var refreshed: Outcome<MangaListSnapshot> = Outcome.Failure(DataError.NoConnection)
        val sent = mutableListOf<Sent>()
        val reads = mutableListOf<SignInId>()

        override fun observe(owner: SignInId) = cache.map { Outcome.Success(it.takeIf { it.owner == owner }) }

        override suspend fun stored(owner: SignInId) = Outcome.Success(cache.value.takeIf { it.owner == owner })

        override suspend fun refresh(
            owner: SignInId,
            canDispatch: () -> Boolean,
        ): Outcome<MangaListSnapshot>? {
            if (!canDispatch()) return null
            reads += owner
            return refreshed.also { if (it is Outcome.Success) cache.value = it.value }
        }

        override suspend fun edit(
            owner: SignInId,
            id: MangaId,
            changes: MangaListEdit,
            retryFrom: MangaListEntry?,
        ): MangaListEditResult {
            sent += Sent(owner, changes, retryFrom)
            return answer.await()
        }
    }

    private fun TestScope.start(backend: Backend): Subject {
        val messages = ShellMessages(backgroundScope, TestGraph.over(backgroundScope).list)
        val editing = MangaEditing(backgroundScope, backend, backend.sessions, messages)
        editing.watch()
        runCurrent()
        editing.open(owner, entry.id, entry.titles.romaji)
        return Subject(editing, messages)
    }

    @Test
    fun acceptedWriteCanOnlyRefreshAndSettlesToTheObservedCount() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            subject.event(MangaEditSheetEvent.ChaptersTyped("26"))
            subject.save()
            subject.saveIfStillAvailable()
            runCurrent()
            assertEquals(
                26,
                subject.editing.state.value
                    .displayed(entry)
                    .chaptersRead,
            )
            backend.answer.complete(MangaListEditResult.Accepted(Outcome.Failure(DataError.ServerError)))
            runCurrent()
            assertEquals(
                12,
                subject.editing.state.value
                    .displayed(entry)
                    .chaptersRead,
            )
            val failure = subject.open.form.submission as MangaEditSubmission.Failed
            assertFalse(failure.canRetry)
            assertTrue(failure.canReconcile)
            subject.event(MangaEditSheetEvent.Retry)
            subject.event(MangaEditSheetEvent.Reconcile)
            runCurrent()
            assertTrue((subject.open.form.submission as MangaEditSubmission.Failed).canReconcile)
            backend.refreshed = Outcome.Success(snapshot(owner, entry.copy(chaptersRead = 24)))
            subject.event(MangaEditSheetEvent.Reconcile)
            runCurrent()
            assertEquals(1, backend.sent.size)
            assertEquals(listOf(owner, owner), backend.reads)
            assertTrue(subject.open.form.closing)
            assertEquals(
                24,
                subject.editing.state.value.snapshot
                    ?.entries
                    ?.single()
                    ?.chaptersRead,
            )
        }

    @Test
    fun uncertainWriteRefreshesBeforeRetryAndPreservesTheOpeningEvidence() =
        runTest {
            val baseline = entry.copy(unverifiedNumbers = setOf(MangaListNumber.ChaptersRead))
            val backend = Backend(baseline)
            val subject = start(backend)
            subject.event(MangaEditSheetEvent.ChaptersTyped("20"))
            subject.event(MangaEditSheetEvent.ScoreChosen(9))
            subject.save()
            runCurrent()
            backend.answer.complete(MangaListEditResult.Uncertain(DataError.ServerError, needsRefresh = true))
            runCurrent()
            subject.event(MangaEditSheetEvent.Retry)
            runCurrent()
            assertEquals(1, backend.sent.size)

            backend.refreshed = Outcome.Success(snapshot(owner, baseline.copy(chaptersRead = 15)))
            subject.event(MangaEditSheetEvent.Reconcile)
            runCurrent()
            assertTrue((subject.open.form.submission as MangaEditSubmission.Failed).canRetry)
            assertEquals("15", subject.open.form.chapters)
            backend.answer = CompletableDeferred(MangaListEditResult.Confirmed(baseline.copy(chaptersRead = 15, score = 9)))
            subject.event(MangaEditSheetEvent.Retry)
            runCurrent()
            assertEquals(Sent(owner, MangaListEdit(score = 9), baseline), backend.sent.last())
        }

    @Test
    fun replacementRetiresPendingAndRejectsTheOldSheetAndItsLateCompletion() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            val original = subject.open
            subject.event(MangaEditSheetEvent.ChaptersTyped("26"))
            subject.save()
            runCurrent()
            subject.event(MangaEditSheetEvent.Dismissed)
            subject.editing.open(owner, entry.id, entry.titles.romaji)
            assertEquals(MangaEditSubmission.Saving, subject.open.form.submission)

            backend.sessions.value = Session.SignedIn(next)
            backend.cache.value = snapshot(next, entry.copy(chaptersRead = 2))
            runCurrent()
            subject.editing.open(next, entry.id, entry.titles.romaji)
            backend.answer.complete(MangaListEditResult.Accepted(Outcome.Failure(DataError.ServerError)))
            subject.editing.event(original, MangaEditSheetEvent.Dismissed)
            runCurrent()
            assertEquals(next, subject.open.owner)
            assertEquals("2", subject.open.form.chapters)
            assertEquals(MangaEditSubmission.Editing, subject.open.form.submission)
            assertNull(subject.messages.current.value)
            assertTrue(
                subject.editing.state.value.pending
                    .isEmpty(),
            )
            assertEquals(listOf(owner), backend.sent.map { it.owner })
            assertTrue(backend.reads.isEmpty())
        }

    @Test
    fun replacementRemovesRecoveryAndRejectsItsStaleAction() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            subject.event(MangaEditSheetEvent.ScoreChosen(9))
            subject.save()
            runCurrent()
            backend.answer.complete(MangaListEditResult.Failed(DataError.Refused))
            runCurrent()
            val stale = checkNotNull(subject.messages.current.value)
            assertTrue(stale.message.action is ShellMessage.Action.MangaRecovery)
            backend.sessions.value = Session.SignedIn(next)
            backend.cache.value = snapshot(next, entry)
            runCurrent()
            assertNull(subject.messages.current.value)
            subject.messages.ended(stale, actionPerformed = true)
            runCurrent()
            assertEquals(1, backend.sent.size)
            assertTrue(backend.reads.isEmpty())
        }

    @Test
    fun displayBaselineUsesCachedNumbersAndDoesNotPromoteTheirEvidence() =
        runTest {
            val migrated = entry.copy(unverifiedNumbers = MangaListNumber.entries.toSet())
            val backend = Backend(migrated)
            val subject = start(backend)
            assertEquals(migrated, subject.open.baseline)
            assertNull(subject.open.baseline.recordedScore)
            assertEquals(8, subject.open.form.score)
            subject.event(MangaEditSheetEvent.ScoreChosen(8))
            assertNull(subject.open.form.saveChanges)
            backend.cache.value = snapshot(owner, entry.copy(score = 10, chaptersRead = 42))
            runCurrent()
            assertEquals("12", subject.open.form.chapters)
            assertEquals(8, subject.open.form.score)

            val ratedZero = entry.copy(score = null, explicitlyUnrated = true)
            val quotedZero = ratedZero.copy(unverifiedNumbers = setOf(MangaListNumber.Score))
            val absent = entry.copy(score = null)
            assertEquals(0, ratedZero.displayBaseline().score)
            assertEquals(0, ratedZero.recordedScore)
            assertEquals(0, quotedZero.displayBaseline().score)
            assertNull(quotedZero.recordedScore)
            assertNull(absent.displayBaseline().score)
        }

    private fun Subject.saveIfStillAvailable() {
        val changes = open.form.saveChanges ?: return
        event(MangaEditSheetEvent.Save(changes))
    }

    private fun snapshot(
        owner: SignInId,
        saved: MangaListEntry,
    ) = MangaListSnapshot(owner, listOf(saved), Instant.EPOCH)
}
