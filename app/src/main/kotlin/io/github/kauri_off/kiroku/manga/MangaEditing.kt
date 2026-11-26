package io.github.kauri_off.kiroku.manga

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListEditResult
import io.github.kauri_off.kiroku.core.repository.MangaListEditor
import io.github.kauri_off.kiroku.core.repository.MangaListRemovalResult
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.core.repository.matchesRemovalRetry
import io.github.kauri_off.kiroku.core.repository.readingStatus
import io.github.kauri_off.kiroku.edit.MangaEditBaseline
import io.github.kauri_off.kiroku.edit.MangaEditChanges
import io.github.kauri_off.kiroku.edit.MangaEditSheetEvent
import io.github.kauri_off.kiroku.edit.MangaEditSheetState
import io.github.kauri_off.kiroku.edit.MangaEditSubmission
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal val LocalMangaEditing = staticCompositionLocalOf<MangaEditing?> { null }

internal data class OpenMangaEditor(
    val instance: Long,
    val owner: SignInId,
    val baseline: MangaListEntry,
    val form: MangaEditSheetState,
)

internal data class MangaEditingState(
    val owner: SignInId? = null,
    val snapshot: MangaListSnapshot? = null,
    val observed: Boolean = false,
    val pending: Map<MangaId, MangaListEdit> = emptyMap(),
    val open: OpenMangaEditor? = null,
) {
    fun displayed(entry: MangaListEntry): MangaListEntry = pending[entry.id]?.appliedTo(entry) ?: entry
}

internal class MangaEditing(
    private val scope: CoroutineScope,
    private val editor: MangaListEditor,
    private val sessions: Flow<Session>,
    private val messages: ShellMessages,
) {
    private enum class Phase { Writing, Reading, Retry, Refresh, RefreshAccepted }

    private data class Operation(
        val owner: SignInId,
        val baseline: MangaListEntry,
        val changes: MangaListEdit?,
        val form: MangaEditSheetState,
        val phase: Phase,
        val action: ShellMessage.Action.MangaRecovery? = null,
    ) {
        val id get() = baseline.id
        val removing get() = changes == null
    }

    private val mutable = MutableStateFlow(MangaEditingState())
    val state = mutable.asStateFlow()
    private val operations = mutableMapOf<MangaId, Operation>()
    private var instance = 0L
    private var watching = false

    fun watch() {
        if (watching) return
        watching = true
        scope.launch {
            sessions.distinctUntilChanged().collectLatest { session ->
                val owner = (session as? Session.SignedIn)?.signIn
                retire(owner)
                if (owner != null) {
                    editor.observe(owner).collect { result ->
                        if (!active(owner)) return@collect
                        when (result) {
                            is Outcome.Success -> {
                                val snapshot = result.value?.takeIf { it.owner == owner }
                                mutable.value = mutable.value.copy(snapshot = snapshot, observed = true)
                                if (snapshot != null) {
                                    operations.values.toList().forEach { op ->
                                        when {
                                            op.phase == Phase.Retry -> readyToRetry(op, snapshot)
                                            op.removing &&
                                                op.phase == Phase.Refresh &&
                                                snapshot.syncedAt != null &&
                                                snapshot.entries.none { it.id == op.id } -> finish(op)
                                        }
                                    }
                                }
                            }
                            is Outcome.Failure ->
                                if (result.error == DataError.ListEnded) retire() else mutable.value = mutable.value.copy(observed = true)
                        }
                    }
                }
            }
        }
    }

    private fun retire(next: SignInId? = null) {
        mutable.value.owner?.let(messages::retireManga)
        operations.clear()
        mutable.value = MangaEditingState(next)
    }

    suspend fun loadMembership(owner: SignInId) {
        val caller = currentCoroutineContext()
        val state = mutable.value
        if (state.snapshot?.syncedAt == null && operations.isEmpty() && active(owner)) {
            editor.refresh(owner) { caller.isActive && mutable.value.owner == owner }
        }
    }

    fun open(
        owner: SignInId,
        id: MangaId,
        title: String,
    ) {
        if (mutable.value.owner != owner || mutable.value.open != null) return
        val entry =
            mutable.value.snapshot
                ?.entries
                ?.find { it.id == id } ?: return
        val operation = operations[id]
        val form = operation?.form?.copy(title = title) ?: MangaEditSheetState(title, entry.displayBaseline(), canRemove = true)
        mutable.value = mutable.value.copy(open = OpenMangaEditor(++instance, owner, operation?.baseline ?: entry, form))
    }

    fun event(
        open: OpenMangaEditor,
        event: MangaEditSheetEvent,
    ) {
        val current = mutable.value.open?.takeIf { it.instance == open.instance && it.owner == mutable.value.owner } ?: return
        when (event) {
            MangaEditSheetEvent.Dismissed -> mutable.value = mutable.value.copy(open = null)
            is MangaEditSheetEvent.Save -> {
                if (event.changes != current.form.saveChanges || operations[current.baseline.id] != null) return
                val op = Operation(current.owner, current.baseline, event.changes.toEdit(), current.form, Phase.Writing)
                write(op, retry = false)
            }
            MangaEditSheetEvent.RemoveConfirmed -> {
                if (!current.form.canRemove || !current.form.confirmRemove || operations[current.baseline.id] != null) return
                val op = Operation(current.owner, current.baseline, null, current.form.showing(current.baseline), Phase.Writing)
                write(op, retry = false)
            }
            MangaEditSheetEvent.Retry, MangaEditSheetEvent.Reconcile -> {
                val op = operations[current.baseline.id] ?: return
                when {
                    event == MangaEditSheetEvent.Retry && op.phase == Phase.Retry -> recover(op)
                    event == MangaEditSheetEvent.Reconcile && op.phase in setOf(Phase.Refresh, Phase.RefreshAccepted) -> recover(op)
                }
            }
            else -> mutable.value = mutable.value.copy(open = current.copy(form = current.form.after(event)))
        }
    }

    private suspend fun active(owner: SignInId): Boolean = sessions.first() == Session.SignedIn(owner) && mutable.value.owner == owner

    private fun reserve(op: Operation) {
        operations[op.id]?.action?.let(messages::remove)
        operations[op.id] = op
        val state = mutable.value
        val open = state.open?.let { if (it.baseline.id == op.id) it.copy(form = op.form.copy(title = it.form.title)) else it }
        mutable.value =
            state.copy(
                pending =
                    operations.values
                        .mapNotNull { writing ->
                            writing.changes?.takeIf { writing.phase == Phase.Writing }?.let { writing.id to it }
                        }.toMap(),
                open = open,
            )
    }

    private fun write(
        op: Operation,
        retry: Boolean,
    ) {
        val submission = if (op.removing) MangaEditSubmission.Removing else MangaEditSubmission.Saving
        val writing = op.copy(phase = Phase.Writing, form = op.form.copy(submission = submission), action = null)
        reserve(writing)
        scope.launch {
            if (!active(writing.owner)) return@launch
            val changes = writing.changes
            if (changes == null) {
                remove(writing, retry)
                return@launch
            }
            val result = editor.edit(writing.owner, writing.id, changes, writing.baseline.takeIf { retry })
            if (!active(writing.owner) || operations[writing.id] !== writing) return@launch
            when (result) {
                is MangaListEditResult.Confirmed -> settle(writing)
                is MangaListEditResult.Accepted ->
                    when (result.readBack) {
                        is Outcome.Success -> settle(writing)
                        Outcome.Failure(DataError.NotFound) -> settle(writing)
                        is Outcome.Failure -> offer(writing, Phase.RefreshAccepted)
                    }
                is MangaListEditResult.Uncertain ->
                    if (result.needsRefresh) offer(writing, Phase.Refresh) else fromStore(writing)
                is MangaListEditResult.Failed ->
                    when (result.error) {
                        DataError.ListEnded, DataError.SessionExpired -> retire()
                        else -> fromStore(writing)
                    }
                MangaListEditResult.RefreshRequired -> offer(writing, Phase.Refresh)
                MangaListEditResult.Discarded -> finish(writing, failed = true)
            }
        }
    }

    private suspend fun remove(
        op: Operation,
        retry: Boolean,
    ) {
        val result = editor.remove(op.owner, op.baseline, retry)
        if (!active(op.owner) || operations[op.id] !== op) return
        when (result) {
            MangaListRemovalResult.Removed -> settle(op)
            is MangaListRemovalResult.Failed ->
                when (result.error) {
                    DataError.ListEnded, DataError.SessionExpired -> retire()
                    else -> offer(op, Phase.Refresh)
                }
            is MangaListRemovalResult.ReadRequired, MangaListRemovalResult.RefreshRequired -> offer(op, Phase.Refresh)
            is MangaListRemovalResult.StillPresent -> if (result.canRetry) fromStore(op) else finish(op, failed = true)
            MangaListRemovalResult.Discarded -> {
                val result = editor.stored(op.owner)
                if (!active(op.owner) || operations[op.id] !== op) return
                if (result == Outcome.Failure(DataError.ListEnded)) {
                    retire()
                    return
                }
                val stored = (result as? Outcome.Success)?.value?.takeIf { it.owner == op.owner }
                stored?.let(::showSnapshot)
                val removed = stored?.syncedAt != null && stored.entries.none { it.id == op.id }
                finish(op, failed = !removed)
            }
        }
    }

    private suspend fun settle(op: Operation) {
        val stored = editor.stored(op.owner)
        if (!active(op.owner) || operations[op.id] !== op) return
        if (stored == Outcome.Failure(DataError.ListEnded)) {
            retire()
            return
        }
        (stored as? Outcome.Success)?.value?.let(::showSnapshot)
        finish(op)
    }

    private fun showSnapshot(snapshot: MangaListSnapshot) {
        val current = mutable.value
        if (current.owner == snapshot.owner) mutable.value = current.copy(snapshot = snapshot, observed = true)
    }

    private suspend fun fromStore(op: Operation) {
        val stored = editor.stored(op.owner)
        if (!active(op.owner) || operations[op.id] !== op) return
        if (stored == Outcome.Failure(DataError.ListEnded)) {
            retire()
            return
        }
        val snapshot = (stored as? Outcome.Success)?.value?.takeIf { it.owner == op.owner }
        if (snapshot == null) {
            offer(op, Phase.Refresh)
        } else {
            showSnapshot(snapshot)
            readyToRetry(op, snapshot)
        }
    }

    private fun readyToRetry(
        op: Operation,
        snapshot: MangaListSnapshot,
    ) {
        val entry = snapshot.entries.find { it.id == op.id }
        val changes = op.changes
        val remaining = if (entry != null) changes?.remainingAfter(op.baseline, entry) else null
        when {
            snapshot.syncedAt == null -> offer(op, Phase.Refresh)
            changes == null ->
                when {
                    entry == null -> finish(op)
                    !op.baseline.matchesRemovalRetry(entry) -> finish(op, failed = true)
                    op.phase != Phase.Retry -> offer(op, Phase.Retry)
                }
            entry == null || remaining == null || remaining.isEmpty -> finish(op, failed = true)
            op.phase != Phase.Retry || remaining != changes ->
                offer(op.copy(changes = remaining, form = op.form.showing(remaining.appliedTo(entry))), Phase.Retry)
        }
    }

    private fun recover(op: Operation) {
        if (operations[op.id] !== op || mutable.value.owner != op.owner) return
        val submission =
            when {
                op.removing && op.phase == Phase.Retry -> MangaEditSubmission.Removing
                op.removing -> MangaEditSubmission.CheckingRemoval
                op.phase == Phase.Retry -> MangaEditSubmission.Saving
                else -> MangaEditSubmission.Reconciling
            }
        val reading = op.copy(phase = Phase.Reading, form = op.form.copy(submission = submission), action = null)
        reserve(reading)
        scope.launch {
            if (!active(op.owner)) return@launch
            val result = if (op.phase == Phase.Retry) editor.stored(op.owner) else editor.refresh(op.owner)
            if (!active(op.owner) || operations[op.id] !== reading) return@launch
            if (result == Outcome.Failure(DataError.ListEnded) || result == Outcome.Failure(DataError.SessionExpired)) {
                retire()
                return@launch
            }
            val snapshot = (result as? Outcome.Success)?.value?.takeIf { it.owner == op.owner }
            snapshot?.let(::showSnapshot)
            val entry = snapshot?.entries?.find { it.id == op.id }
            val changes = op.changes
            when {
                snapshot == null -> offer(reading, if (op.phase == Phase.Retry) Phase.Refresh else op.phase)
                snapshot.syncedAt == null -> offer(reading, if (op.phase == Phase.RefreshAccepted) op.phase else Phase.Refresh)
                changes == null ->
                    if (op.phase == Phase.Retry && entry != null && op.baseline.matchesRemovalRetry(entry)) {
                        write(reading, retry = true)
                    } else {
                        readyToRetry(reading, snapshot)
                    }
                op.phase == Phase.RefreshAccepted ->
                    if (entry == null || changes.hasEvidenceIn(entry)) finish(reading) else offer(reading, Phase.RefreshAccepted)
                entry == null -> finish(reading, failed = true)
                op.phase == Phase.Refresh && changes.isPresentIn(entry) -> finish(reading)
                op.phase == Phase.Refresh -> readyToRetry(reading, snapshot)
                else -> {
                    val remaining = changes.remainingAfter(op.baseline, entry)
                    if (remaining.isEmpty) {
                        finish(reading, failed = true)
                    } else {
                        write(reading.copy(changes = remaining, form = op.form.showing(remaining.appliedTo(entry))), retry = true)
                    }
                }
            }
        }
    }

    private fun offer(
        op: Operation,
        phase: Phase,
    ) {
        val refresh = phase != Phase.Retry
        val text =
            when {
                op.removing && refresh -> ShellMessage.Text.MangaRemovalUnconfirmed
                op.removing -> ShellMessage.Text.MangaCouldNotRemove
                refresh -> ShellMessage.Text.MangaUnconfirmed
                else -> ShellMessage.Text.CouldNotSave
            }
        val label =
            when {
                op.removing && refresh -> R.string.manga_edit_removal_unconfirmed
                op.removing -> R.string.manga_edit_could_not_remove
                refresh -> R.string.manga_edit_unconfirmed
                else -> R.string.shell_could_not_save
            }
        lateinit var available: Operation
        val action = ShellMessage.Action.MangaRecovery(refresh) { recover(available) }
        val failure =
            MangaEditSubmission.Failed(
                canRetry = !refresh,
                message = label,
                canReconcile = refresh,
            )
        available = op.copy(phase = phase, form = op.form.copy(submission = failure), action = action)
        reserve(available)
        messages.post(ShellMessage(text, action, mangaOwner = op.owner))
    }

    private fun finish(
        op: Operation,
        failed: Boolean = false,
    ) {
        operations.remove(op.id)?.action?.let(messages::remove)
        val state = mutable.value
        val open =
            state.open?.let {
                if (it.baseline.id != op.id) {
                    it
                } else {
                    val form =
                        if (failed) {
                            val label = if (op.removing) R.string.manga_edit_could_not_remove else R.string.shell_could_not_save
                            it.form.copy(submission = MangaEditSubmission.Failed(canRetry = false, message = label))
                        } else {
                            it.form.copy(closing = true)
                        }
                    it.copy(form = form)
                }
            }
        mutable.value = state.copy(pending = state.pending - op.id, open = open)
        if (failed && open?.baseline?.id != op.id) messages.post(ShellMessage(ShellMessage.Text.CouldNotSave, mangaOwner = op.owner))
    }
}

private fun MangaEditSheetState.showing(entry: MangaListEntry) = MangaEditSheetState(title, entry.displayBaseline(), canRemove = true)

internal fun MangaListEntry.displayBaseline(): MangaEditBaseline =
    MangaEditBaseline(status, chaptersRead, volumesRead, score ?: 0.takeIf { explicitlyUnrated })

private fun MangaEditChanges.toEdit(): MangaListEdit =
    MangaListEdit(
        status = status?.let { choice -> MangaListStatus.entries.single { it.readingStatus == choice.readingStatus } },
        score = score,
        chaptersRead = chaptersRead,
        volumesRead = volumesRead,
    )
