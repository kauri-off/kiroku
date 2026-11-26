package io.github.kauri_off.kiroku.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListNumber
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListEditResult
import io.github.kauri_off.kiroku.core.repository.MangaListEditor
import io.github.kauri_off.kiroku.core.repository.MangaListRemovalResult
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.core.repository.readingStatus
import io.github.kauri_off.kiroku.edit.MangaEditSheetEvent
import io.github.kauri_off.kiroku.edit.MangaEditSubmission
import io.github.kauri_off.kiroku.edit.MangaEditTotals
import io.github.kauri_off.kiroku.shell.MangaChange
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
        var storeError: DataError? = null
        var observations: Flow<Outcome<MangaListSnapshot?>>? = null
        var refreshAdmission = CompletableDeferred(Unit)
        var answer = CompletableDeferred<MangaListEditResult>()
        var removalAnswer = CompletableDeferred<MangaListRemovalResult>()
        var addAnswer = CompletableDeferred<MangaListEditResult>()
        val adds = mutableListOf<Pair<SignInId, MangaId>>()
        val removals = mutableListOf<Triple<SignInId, MangaListEntry, Boolean>>()
        var refreshed: Outcome<MangaListSnapshot> = Outcome.Failure(DataError.NoConnection)
        val sent = mutableListOf<Sent>()
        val reads = mutableListOf<SignInId>()

        override fun observe(owner: SignInId) = observations ?: cache.map { Outcome.Success(it.takeIf { it.owner == owner }) }

        override suspend fun stored(owner: SignInId): Outcome<MangaListSnapshot?> =
            storeError?.let { Outcome.Failure(it) } ?: Outcome.Success(cache.value.takeIf { it.owner == owner })

        override suspend fun refresh(
            owner: SignInId,
            canDispatch: () -> Boolean,
        ): Outcome<MangaListSnapshot>? {
            refreshAdmission.await()
            if (!canDispatch()) return null
            reads += owner
            return refreshed.also { if (it is Outcome.Success) cache.value = it.value }
        }

        override suspend fun remove(
            owner: SignInId,
            baseline: MangaListEntry,
            retry: Boolean,
        ): MangaListRemovalResult {
            removals += Triple(owner, baseline, retry)
            return removalAnswer.await()
        }

        override suspend fun addPlanned(
            owner: SignInId,
            id: MangaId,
        ): MangaListEditResult {
            adds += owner to id
            return addAnswer.await()
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
    fun addRequiresReadableConfirmedAbsenceAndAStillActiveSignIn() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            subject.editing.dismissPresentation()
            val id = entry.id
            backend.cache.value = MangaListSnapshot(owner, emptyList(), null)
            runCurrent()
            subject.add()
            assertFalse(
                subject.editing.state.value
                    .canAdd(id),
            )

            backend.storeError = DataError.StorageError
            backend.cache.value = MangaListSnapshot(owner, emptyList(), Instant.EPOCH)
            runCurrent()
            assertFalse(
                subject.editing.state.value
                    .canAdd(id),
            )
            subject.add()
            assertTrue(backend.adds.isEmpty())

            backend.storeError = null
            backend.cache.value = backend.cache.value.copy(syncedAt = Instant.EPOCH.plusSeconds(1))
            runCurrent()
            assertTrue(
                subject.editing.state.value
                    .canAdd(id),
            )
            subject.add()
            subject.add()
            assertFalse(
                subject.editing.state.value
                    .canAdd(id),
            )
            runCurrent()
            assertEquals(listOf(owner to id), backend.adds)

            backend.sessions.value = Session.Expired(owner)
            runCurrent()
            assertFalse(
                subject.editing.state.value
                    .canAdd(id),
            )
            backend.sessions.value = Session.SignedIn(next)
            runCurrent()
            backend.addAnswer.complete(MangaListEditResult.Confirmed(entry))
            runCurrent()
            assertNull(subject.messages.current.value)
            assertFalse(
                subject.editing.state.value
                    .canAdd(id),
            )
        }

    @Test
    fun confirmedAddKeepsALaterDirtySnapshotAndDoesNotRequireReadBack() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            val empty = MangaListSnapshot(owner, emptyList(), Instant.EPOCH)
            val planned = entry.copy(status = MangaListStatus.PlanToRead.readingStatus)
            val newer = snapshot(owner, planned.copy(chaptersRead = 15)).copy(syncedAt = null)
            backend.cache.value = empty
            runCurrent()
            subject.add()
            runCurrent()
            backend.cache.value = newer
            backend.addAnswer.complete(MangaListEditResult.Confirmed(planned))
            runCurrent()

            assertEquals(newer, subject.editing.state.value.snapshot)
            assertTrue(
                subject.editing.state.value.reconcilingAdd
                    .isEmpty(),
            )
            val message = checkNotNull(subject.messages.current.value).message
            assertEquals(ShellMessage.Text.AddedToPlanned, message.text)
            assertTrue(message.action is MangaChange)
        }

    @Test
    fun completedAndSkippedAddCannotOfferRetryAfterALaterRemoval() =
        runTest {
            val planned = entry.copy(status = MangaListStatus.PlanToRead.readingStatus)
            for (result in listOf(MangaListEditResult.Confirmed(planned), MangaListEditResult.Discarded)) {
                val backend = Backend()
                val subject = start(backend)
                val empty = MangaListSnapshot(owner, emptyList(), Instant.EPOCH)
                backend.cache.value = empty
                runCurrent()
                subject.add()
                runCurrent()
                val later = empty.copy(syncedAt = Instant.EPOCH.plusSeconds(1))
                backend.cache.value = later
                backend.addAnswer.complete(result)
                runCurrent()

                assertEquals(later, subject.editing.state.value.snapshot)
                assertTrue(
                    subject.editing.state.value
                        .canAdd(entry.id),
                )
                val message =
                    subject.messages.current.value
                        ?.message
                assertNull(message?.action)
                assertEquals(ShellMessage.Text.AddedToPlanned.takeIf { result is MangaListEditResult.Confirmed }, message?.text)
                assertEquals(listOf(owner to entry.id), backend.adds)
            }
        }

    @Test
    fun confirmedRemovalRetiresOnlyItsAddChangeAndCannotDispatchItAgain() =
        runTest {
            for (requested in listOf(false, true)) {
                val backend = Backend()
                val subject = start(backend)
                subject.editing.dismissPresentation()
                val empty = MangaListSnapshot(owner, emptyList(), Instant.EPOCH)
                val planned = entry.copy(status = MangaListStatus.PlanToRead.readingStatus)
                backend.cache.value = empty
                runCurrent()
                subject.add()
                runCurrent()
                backend.cache.value = snapshot(owner, planned)
                backend.addAnswer.complete(MangaListEditResult.Confirmed(planned))
                runCurrent()
                val added = checkNotNull(subject.messages.current.value)
                if (requested) {
                    subject.messages.ended(added, actionPerformed = true)
                    assertTrue(subject.messages.mangaEditRequest.value != null)
                }

                subject.editing.open(owner, entry.id, entry.titles.romaji)
                subject.confirmRemove()
                runCurrent()
                backend.cache.value = empty
                backend.removalAnswer.complete(MangaListRemovalResult.Removed)
                runCurrent()
                assertNull(subject.messages.current.value)
                subject.messages.ended(added, actionPerformed = true)
                assertNull(subject.messages.mangaEditRequest.value)
            }
        }

    @Test
    fun confirmedRemovalLeavesAnotherTitlesAddChangeAvailable() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            val otherId = MangaId(entry.id.value + 1)
            val change = MangaChange(otherId, owner, entry.titles.romaji, MangaEditTotals())
            subject.messages.post(ShellMessage(ShellMessage.Text.AddedToPlanned, change, mangaOwner = owner))
            subject.confirmRemove()
            runCurrent()
            backend.cache.value = MangaListSnapshot(owner, emptyList(), Instant.EPOCH)
            backend.removalAnswer.complete(MangaListRemovalResult.Removed)
            runCurrent()
            assertEquals(
                change,
                subject.messages.current.value
                    ?.message
                    ?.action,
            )
        }

    @Test
    fun uncertainAddReconcilesWithoutResendingAndOnlyOffersRetryAfterConfirmedAbsence() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            subject.editing.dismissPresentation()
            val empty = MangaListSnapshot(owner, emptyList(), Instant.EPOCH)
            backend.cache.value = empty
            runCurrent()
            subject.add()
            runCurrent()
            backend.cache.value = empty.copy(syncedAt = null)
            backend.addAnswer.complete(MangaListEditResult.Uncertain(DataError.ServerError, needsRefresh = true))
            runCurrent()

            val refresh = checkNotNull(subject.messages.current.value)
            assertEquals(ShellMessage.Text.MangaUnconfirmed, refresh.message.text)
            assertFalse(
                subject.editing.state.value
                    .canAdd(entry.id),
            )
            subject.add()
            assertEquals(1, backend.adds.size)

            subject.messages.ended(refresh, actionPerformed = true)
            runCurrent()
            assertEquals(1, backend.adds.size)
            assertEquals(listOf(owner), backend.reads)
            assertEquals(
                ShellMessage.Text.MangaUnconfirmed,
                subject.messages.current.value
                    ?.message
                    ?.text,
            )

            backend.refreshed = Outcome.Success(empty)
            subject.messages.ended(checkNotNull(subject.messages.current.value), actionPerformed = true)
            runCurrent()
            assertEquals(
                ShellMessage.Text.CouldNotSave,
                subject.messages.current.value
                    ?.message
                    ?.text,
            )
            assertEquals(1, backend.adds.size)

            backend.addAnswer = CompletableDeferred()
            subject.messages.ended(checkNotNull(subject.messages.current.value), actionPerformed = true)
            runCurrent()
            assertEquals(listOf(owner to entry.id, owner to entry.id), backend.adds)
        }

    @Test
    fun readBackOfAnUncertainAddShowsConfirmedPlannedAndInvalidatesTheOldAction() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            subject.editing.dismissPresentation()
            backend.cache.value = MangaListSnapshot(owner, emptyList(), Instant.EPOCH)
            runCurrent()
            subject.add()
            runCurrent()
            backend.cache.value = backend.cache.value.copy(syncedAt = null)
            backend.addAnswer.complete(MangaListEditResult.Accepted(Outcome.Failure(DataError.ServerError)))
            runCurrent()
            val stale = checkNotNull(subject.messages.current.value)
            val planned = entry.copy(status = MangaListStatus.PlanToRead.readingStatus)
            backend.cache.value = snapshot(owner, planned)
            runCurrent()
            subject.messages.ended(stale, actionPerformed = true)
            runCurrent()
            assertEquals(listOf(owner to entry.id), backend.adds)
            assertTrue(backend.reads.isEmpty())
            val added = checkNotNull(subject.messages.current.value)
            assertEquals(ShellMessage.Text.AddedToPlanned, added.message.text)
            val change = added.message.action as MangaChange
            assertEquals(entry.id to owner, change.id to change.owner)
        }

    private fun Subject.add() = editing.addPlanned(owner, entry.id, entry.titles.romaji, MangaEditTotals())

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
            assertFalse(failure.canStartOver)
            assertTrue(failure.canReconcile)
            subject.event(MangaEditSheetEvent.Retry)
            subject.event(MangaEditSheetEvent.StartOver)
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

    @Test
    fun removeRequiresConfirmationAndClosesWhenAbsenceIsConfirmed() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            subject.event(MangaEditSheetEvent.RemoveConfirmed)
            subject.event(MangaEditSheetEvent.RemoveAsked)
            assertTrue(subject.open.form.confirmRemove)
            subject.event(MangaEditSheetEvent.CancelRemove)
            runCurrent()
            assertTrue(backend.removals.isEmpty())

            subject.confirmRemove()
            subject.event(MangaEditSheetEvent.RemoveConfirmed)
            runCurrent()
            assertEquals(MangaEditSubmission.Removing, subject.open.form.submission)
            assertEquals(listOf(Triple(owner, entry, false)), backend.removals)
            assertEquals(
                entry,
                subject.editing.state.value.snapshot
                    ?.entries
                    ?.single(),
            )
            backend.cache.value = backend.cache.value.copy(entries = emptyList())
            backend.removalAnswer.complete(MangaListRemovalResult.Removed)
            runCurrent()
            assertTrue(subject.open.form.closing)
            assertEquals(
                emptyList<MangaListEntry>(),
                subject.editing.state.value.snapshot
                    ?.entries,
            )
        }

    @Test
    fun removalRefusalNeedsACompleteReadAndGuardedRetry() =
        runTest {
            val baseline = entry.copy(updatedAt = Instant.EPOCH)
            val backend = Backend(baseline)
            val subject = start(backend)
            subject.confirmRemove()
            runCurrent()
            backend.removalAnswer.complete(MangaListRemovalResult.Failed(DataError.Refused, needsRefresh = true))
            runCurrent()
            subject.event(MangaEditSheetEvent.Retry)
            runCurrent()
            assertEquals(1, backend.removals.size)

            subject.event(MangaEditSheetEvent.Reconcile)
            runCurrent()
            assertTrue((subject.open.form.submission as MangaEditSubmission.Failed).canReconcile)
            backend.refreshed = Outcome.Success(snapshot(owner, baseline))
            subject.event(MangaEditSheetEvent.Reconcile)
            runCurrent()
            assertEquals(listOf(owner, owner), backend.reads)
            assertTrue((subject.open.form.submission as MangaEditSubmission.Failed).canRetry)
            backend.removalAnswer = CompletableDeferred()
            subject.event(MangaEditSheetEvent.Retry)
            runCurrent()
            assertEquals(Triple(owner, baseline, true), backend.removals.last())
        }

    @Test
    fun ordinaryRefreshSettlesRemovalRecoveryWithoutDownloadingAgain() =
        runTest {
            for (matches in listOf(true, false)) {
                val baseline = entry.copy(updatedAt = Instant.EPOCH)
                val backend = Backend(baseline)
                val subject = start(backend)
                subject.confirmRemove()
                runCurrent()
                backend.removalAnswer.complete(MangaListRemovalResult.Failed(DataError.Refused))
                runCurrent()

                assertTrue((subject.open.form.submission as MangaEditSubmission.Failed).canReconcile)
                val refreshed = if (matches) baseline else baseline.copy(chaptersRead = 21)
                backend.cache.value = snapshot(owner, refreshed)
                backend.storeError = DataError.StorageError
                subject.editing.listRefreshed(backend.cache.value)
                runCurrent()

                val failure = subject.open.form.submission as MangaEditSubmission.Failed
                assertEquals(matches, failure.canRetry)
                assertFalse(failure.canReconcile)
                assertEquals(1, backend.removals.size)
                assertTrue(backend.reads.isEmpty())
            }
        }

    @Test
    fun discardedRetrySettlesWhenAnotherRefreshConfirmedTheEdit() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            subject.event(MangaEditSheetEvent.ChaptersTyped("26"))
            subject.save()
            runCurrent()
            backend.answer.complete(MangaListEditResult.Failed(DataError.Refused))
            runCurrent()
            backend.answer = CompletableDeferred()
            subject.event(MangaEditSheetEvent.Retry)
            runCurrent()
            assertEquals(2, backend.sent.size)
            backend.cache.value = snapshot(owner, entry.copy(chaptersRead = 26))
            subject.editing.listRefreshed(backend.cache.value)
            runCurrent()
            backend.answer.complete(MangaListEditResult.Discarded)
            runCurrent()

            assertTrue(subject.open.form.closing)
            assertNull(subject.messages.current.value)
        }

    @Test
    fun withdrawalOfRemovalRetryOnAnObservedChangePreventsAStaleAction() =
        runTest {
            val baseline = entry.copy(updatedAt = Instant.EPOCH)
            val backend = Backend(baseline)
            val subject = start(backend)
            subject.confirmRemove()
            runCurrent()
            backend.removalAnswer.complete(MangaListRemovalResult.StillPresent(canRetry = true))
            runCurrent()
            val stale = checkNotNull(subject.messages.current.value)
            assertTrue((subject.open.form.submission as MangaEditSubmission.Failed).canRetry)
            backend.cache.value = snapshot(owner, baseline.copy(chaptersRead = 21))
            runCurrent()
            assertFalse((subject.open.form.submission as MangaEditSubmission.Failed).canRetry)
            assertNull(subject.messages.current.value)
            subject.messages.ended(stale, actionPerformed = true)
            subject.event(MangaEditSheetEvent.Retry)
            runCurrent()
            assertEquals(1, backend.removals.size)
        }

    @Test
    fun removalRefreshCanEstablishAbsenceWithNoResend() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            subject.confirmRemove()
            runCurrent()
            backend.removalAnswer.complete(MangaListRemovalResult.ReadRequired(DataError.NoConnection))
            runCurrent()
            backend.refreshed = Outcome.Success(backend.cache.value.copy(entries = emptyList()))
            subject.event(MangaEditSheetEvent.Reconcile)
            runCurrent()
            assertTrue(subject.open.form.closing)
            assertEquals(1, backend.removals.size)
        }

    @Test
    fun replacementRejectsAFormerOwnersRemovalConfirmationAndDelayedRecovery() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            subject.confirmRemove()
            val old = subject.open
            runCurrent()
            backend.sessions.value = Session.SignedIn(next)
            backend.cache.value = snapshot(next, entry)
            runCurrent()
            subject.editing.open(next, entry.id, entry.titles.romaji)
            subject.editing.event(old, MangaEditSheetEvent.RemoveConfirmed)
            backend.removalAnswer.complete(MangaListRemovalResult.ReadRequired(DataError.NoConnection))
            runCurrent()
            assertEquals(next, subject.open.owner)
            assertTrue(subject.open.form.canRemove)
            assertNull(subject.messages.current.value)
            assertEquals(listOf(Triple(owner, entry, false)), backend.removals)
            assertTrue(backend.reads.isEmpty())
        }

    @Test
    fun completeObservationSettlesDismissedAcknowledgedAndRetryEligibleEdits() =
        runTest {
            for (result in listOf(
                MangaListEditResult.Accepted(Outcome.Failure(DataError.NoConnection)),
                MangaListEditResult.Failed(DataError.Refused),
            )) {
                val backend = Backend()
                val subject = start(backend)
                subject.event(MangaEditSheetEvent.ChaptersTyped("26"))
                subject.save()
                runCurrent()
                backend.answer.complete(result)
                runCurrent()
                subject.event(MangaEditSheetEvent.Dismissed)

                backend.cache.value = snapshot(owner, entry.copy(chaptersRead = 26))
                runCurrent()
                assertNull(subject.messages.current.value)
                subject.editing.open(owner, entry.id, entry.titles.romaji)
                assertEquals("26", subject.open.form.chapters)
                assertEquals(MangaEditSubmission.Editing, subject.open.form.submission)
                assertEquals(1, backend.sent.size)
                assertTrue(backend.reads.isEmpty())
            }
        }

    @Test
    fun anotherTitlesDebtClearingRestoresRetryWithoutAnExtraRead() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            val other = entry.copy(id = MangaId(99))
            backend.cache.value = backend.cache.value.copy(entries = listOf(entry, other))
            subject.event(MangaEditSheetEvent.ChaptersTyped("26"))
            subject.save()
            runCurrent()
            backend.answer.complete(MangaListEditResult.Failed(DataError.Refused))
            runCurrent()
            subject.event(MangaEditSheetEvent.Dismissed)

            backend.answer = CompletableDeferred()
            subject.editing.open(owner, other.id, other.titles.romaji)
            subject.event(MangaEditSheetEvent.ChaptersTyped("28"))
            subject.save()
            runCurrent()
            backend.cache.value = backend.cache.value.copy(syncedAt = null)
            runCurrent()
            val refresh =
                subject.messages.current.value
                    ?.message
                    ?.action as ShellMessage.Action.MangaRecovery
            assertTrue(refresh.readOnly)
            val saved = other.copy(chaptersRead = 28)
            backend.cache.value = MangaListSnapshot(owner, listOf(entry, saved), Instant.EPOCH)
            backend.answer.complete(MangaListEditResult.Confirmed(saved))
            runCurrent()
            val retry =
                subject.messages.current.value
                    ?.message
                    ?.action as ShellMessage.Action.MangaRecovery
            assertFalse(retry.readOnly)
            assertEquals(2, backend.sent.size)
            assertTrue(backend.reads.isEmpty())
        }

    @Test
    fun anotherTitlesReadBackCanSettleAnAcknowledgedEditWithVerifiedValues() =
        runTest {
            val backend = Backend()
            val other = entry.copy(id = MangaId(99), chaptersRead = 18)
            backend.cache.value = MangaListSnapshot(owner, listOf(entry, other), Instant.EPOCH)
            backend.observations = flowOf(Outcome.Success(backend.cache.value))
            val subject = start(backend)
            subject.event(MangaEditSheetEvent.ChaptersTyped("26"))
            subject.save()
            runCurrent()
            backend.answer.complete(MangaListEditResult.Accepted(Outcome.Failure(DataError.NoConnection)))
            runCurrent()
            subject.event(MangaEditSheetEvent.Dismissed)

            val unverified = entry.copy(chaptersRead = 26, unverifiedNumbers = setOf(MangaListNumber.ChaptersRead))
            for ((index, current) in listOf(unverified, entry.copy(chaptersRead = 25)).withIndex()) {
                backend.answer = CompletableDeferred()
                subject.editing.open(owner, other.id, other.titles.romaji)
                val chapters = 19 + index
                subject.event(MangaEditSheetEvent.ChaptersTyped(chapters.toString()))
                subject.save()
                runCurrent()
                val saved = other.copy(chaptersRead = chapters)
                backend.cache.value = MangaListSnapshot(owner, listOf(current, saved), Instant.EPOCH)
                backend.answer.complete(MangaListEditResult.Confirmed(saved))
                runCurrent()
                subject.event(MangaEditSheetEvent.Dismissed)
                subject.editing.open(owner, entry.id, entry.titles.romaji)
                if (index == 0) {
                    assertFalse((subject.open.form.submission as MangaEditSubmission.Failed).canRetry)
                    subject.event(MangaEditSheetEvent.Dismissed)
                }
            }
            assertEquals(MangaEditSubmission.Editing, subject.open.form.submission)
            assertEquals("25", subject.open.form.chapters)
            assertEquals(listOf(26, 19, 20), backend.sent.map { it.changes.chaptersRead })
            assertTrue(backend.reads.isEmpty())
        }

    @Test
    fun requestLevelExpiryDoesNotDisableEditingForThePublishedSignIn() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            subject.event(MangaEditSheetEvent.ChaptersTyped("26"))
            subject.save()
            runCurrent()
            backend.answer.complete(MangaListEditResult.Failed(DataError.SessionExpired))
            runCurrent()
            assertEquals(Session.SignedIn(owner), backend.sessions.value)
            assertTrue(subject.editing.state.value.signedIn)
            assertTrue((subject.open.form.submission as MangaEditSubmission.Failed).canRetry)
            subject.event(MangaEditSheetEvent.Dismissed)
            backend.cache.value = snapshot(owner, entry.copy(chaptersRead = 26))
            subject.editing.listRefreshed(backend.cache.value)
            runCurrent()
            subject.editing.open(owner, entry.id, entry.titles.romaji)
            assertEquals("26", subject.open.form.chapters)
            assertEquals(MangaEditSubmission.Editing, subject.open.form.submission)
        }

    @Test
    fun delayedObservationAndExpiryCannotRestoreAConfirmedRemoval() =
        runTest {
            val backend = Backend()
            val observed = MutableSharedFlow<Outcome<MangaListSnapshot?>>(replay = 1)
            val before = backend.cache.value
            observed.emit(Outcome.Success(before))
            backend.observations = observed
            val subject = start(backend)
            subject.confirmRemove()
            runCurrent()

            backend.cache.value = before.copy(entries = emptyList())
            backend.removalAnswer.complete(MangaListRemovalResult.Removed)
            runCurrent()
            assertTrue(subject.open.form.closing)
            observed.emit(Outcome.Success(before))
            runCurrent()
            assertTrue(
                subject.editing.state.value.snapshot
                    ?.entries
                    ?.isEmpty() == true,
            )
            backend.sessions.value = Session.Expired(owner)
            runCurrent()
            assertTrue(
                subject.editing.state.value.snapshot
                    ?.entries
                    ?.isEmpty() == true,
            )
            assertFalse(subject.editing.state.value.signedIn)
            assertNull(subject.editing.state.value.open)
        }

    @Test
    fun aListRefreshRepairsTheSnapshotAfterObservationStops() =
        runTest {
            val backend = Backend()
            backend.observations = flowOf(Outcome.Success(backend.cache.value), Outcome.Failure(DataError.StorageError))
            val subject = start(backend)
            subject.event(MangaEditSheetEvent.Dismissed)
            backend.cache.value = snapshot(owner, entry.copy(chaptersRead = 24))
            subject.editing.listRefreshed(backend.cache.value)
            runCurrent()
            subject.editing.open(owner, entry.id, entry.titles.romaji)
            assertEquals("24", subject.open.form.chapters)
        }

    @Test
    fun startOverAbandonsTheFailedOperationAndItsRecoveryCallback() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            subject.event(MangaEditSheetEvent.ChaptersTyped("26"))
            subject.save()
            runCurrent()
            backend.answer.complete(MangaListEditResult.Failed(DataError.Refused))
            runCurrent()
            subject.event(MangaEditSheetEvent.Retry)
            runCurrent()
            val stale = checkNotNull(subject.messages.current.value)
            subject.event(MangaEditSheetEvent.Dismissed)
            subject.editing.open(owner, entry.id, entry.titles.romaji)
            subject.event(MangaEditSheetEvent.StartOver)
            assertEquals("12", subject.open.form.chapters)
            subject.messages.ended(stale, actionPerformed = true)
            backend.answer = CompletableDeferred()
            subject.event(MangaEditSheetEvent.ChaptersTyped("13"))
            subject.save()
            runCurrent()
            assertEquals(listOf(26, 26, 13), backend.sent.map { it.changes.chaptersRead })
        }

    @Test
    fun terminalRemovalAfterDismissalUsesTheNamedRemovalFailure() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            subject.confirmRemove()
            runCurrent()
            subject.event(MangaEditSheetEvent.Dismissed)
            backend.removalAnswer.complete(MangaListRemovalResult.StillPresent(canRetry = false))
            runCurrent()
            assertEquals(
                ShellMessage.Text.MangaCouldNotRemove(entry.titles.romaji),
                subject.messages.current.value
                    ?.message
                    ?.text,
            )
        }

    @Test
    fun forcedMembershipRefreshUpdatesACompleteCacheAndQueuedReadDefersWhenCovered() =
        runTest {
            val backend = Backend()
            val subject = start(backend)
            val online = MutableStateFlow(true)
            val connection =
                object : Connection {
                    override fun observeOnline() = online
                }
            val membership = MangaMembership(backgroundScope, connection, subject.editing, owner)
            val shown = backgroundScope.launch { membership.shown.collect {} }
            runCurrent()
            assertTrue(backend.reads.isEmpty())

            backend.refreshed = Outcome.Success(snapshot(owner, entry.copy(chaptersRead = 39)))
            backend.refreshAdmission = CompletableDeferred()
            membership.request(force = true)
            runCurrent()
            shown.cancel()
            runCurrent()
            backend.refreshAdmission.complete(Unit)
            runCurrent()
            assertTrue(backend.reads.isEmpty())

            backgroundScope.launch { membership.shown.collect {} }
            runCurrent()
            assertEquals(listOf(owner), backend.reads)
            assertEquals(
                39,
                subject.editing.state.value.snapshot
                    ?.entries
                    ?.single()
                    ?.chaptersRead,
            )
        }

    private fun Subject.confirmRemove() {
        event(MangaEditSheetEvent.RemoveAsked)
        event(MangaEditSheetEvent.RemoveConfirmed)
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
