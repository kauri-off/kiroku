package io.github.kauri_off.kiroku.manga

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
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
import io.github.kauri_off.kiroku.edit.MangaEditTotals
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.MangaChange
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val LocalMangaEditing = staticCompositionLocalOf<MangaEditing?> { null }

internal val LocalMangaEditOrigin = staticCompositionLocalOf<MangaEditOrigin?> { null }

internal data class MangaEditOrigin(
    val destination: Destination,
    val page: NavKey,
    val generation: Int,
    val inMangaList: Boolean,
)

internal data class OpenMangaEditor(
    val instance: Long,
    val owner: SignInId,
    val baseline: MangaListEntry,
    val form: MangaEditSheetState,
    val origin: MangaEditOrigin?,
)

internal data class MangaEditingState(
    val owner: SignInId? = null,
    val snapshot: MangaListSnapshot? = null,
    val observed: Boolean = false,
    val snapshotReadable: Boolean = false,
    val signedIn: Boolean = false,
    val pending: Map<MangaId, MangaListEdit> = emptyMap(),
    val open: OpenMangaEditor? = null,
    val adding: Set<MangaId> = emptySet(),
    val reconcilingAdd: Set<MangaId> = emptySet(),
) {
    fun canAdd(id: MangaId): Boolean =
        signedIn &&
            snapshotReadable &&
            snapshot?.syncedAt != null &&
            snapshot.entries.none { it.id == id } &&
            id !in adding &&
            id !in reconcilingAdd

    fun displayed(entry: MangaListEntry): MangaListEntry = pending[entry.id]?.appliedTo(entry) ?: entry
}

internal class MangaEditing(
    private val scope: CoroutineScope,
    private val editor: MangaListEditor,
    private val sessions: Flow<Session>,
    private val messages: ShellMessages,
) {
    private enum class Phase { Writing, Reading, Retry, Refresh, RefreshRetry, RefreshAccepted }

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

    private data class Addition(
        val change: MangaChange,
        val recovery: ShellMessage.Action.MangaRecovery,
    )

    private val additions = mutableMapOf<MangaId, Addition>()
    private val mutable = MutableStateFlow(MangaEditingState())
    val state = mutable.asStateFlow()
    private val operations = mutableMapOf<MangaId, Operation>()
    private var instance = 0L
    private var watching = false

    private val snapshotReads = Mutex()
    private val observation = MutableStateFlow(0)
    private var observerStopped = false

    fun watch() {
        if (watching) {
            if (observerStopped) observation.value++
            return
        }
        watching = true
        scope.launch {
            sessions.distinctUntilChanged().collectLatest { session ->
                retire(session.owner, session is Session.SignedIn)
                val owner = session.owner ?: return@collectLatest
                observation.collectLatest {
                    observerStopped = false
                    editor.observe(owner).collect {
                        readSnapshot(owner)
                    }
                    observerStopped = true
                }
            }
        }
    }

    private fun settleRecoveries(snapshot: MangaListSnapshot) {
        if (snapshot.syncedAt != null) {
            additions.values.toList().forEach { addition ->
                val change = addition.change
                val entry = snapshot.entries.find { it.id == change.id }
                when {
                    entry != null -> finishAdd(change, added = addition.recovery.readOnly && entry.isPlanned)
                    addition.recovery.readOnly -> offerAddRecovery(change, readOnly = false)
                }
            }
        }
        operations.values.toList().forEach { op ->
            val entry = snapshot.entries.find { it.id == op.id }
            when (op.phase) {
                Phase.Retry -> readyToRetry(op, snapshot)
                Phase.RefreshRetry -> if (snapshot.syncedAt != null) readyToRetry(op, snapshot)
                Phase.Refresh ->
                    if (snapshot.syncedAt != null) {
                        if (!op.removing) {
                            readyToRetry(op, snapshot)
                        } else if (entry == null) {
                            finish(op)
                        }
                    }
                Phase.RefreshAccepted -> if (acceptedSettled(op, snapshot, entry)) finish(op)
                Phase.Writing, Phase.Reading -> Unit
            }
        }
    }

    private fun acceptedSettled(
        op: Operation,
        snapshot: MangaListSnapshot,
        entry: MangaListEntry?,
    ): Boolean = snapshot.syncedAt != null && (entry == null || op.changes?.hasEvidenceIn(entry) == true)

    private fun retire(
        next: SignInId?,
        signedIn: Boolean,
    ) {
        val previous = mutable.value
        previous.owner?.let(messages::retireManga)
        operations.clear()
        additions.clear()
        mutable.value =
            MangaEditingState(
                owner = next,
                snapshot = previous.snapshot.takeIf { next != null && next == previous.owner },
                observed = previous.observed && next != null && next == previous.owner,
                signedIn = signedIn,
            )
    }

    private suspend fun readSnapshot(owner: SignInId): Outcome<MangaListSnapshot?> =
        snapshotReads.withLock {
            val stored = editor.stored(owner)
            val session = sessions.first()
            if (session.owner != owner || mutable.value.owner != owner) return@withLock Outcome.Failure(DataError.ListEnded)
            when (stored) {
                is Outcome.Success -> {
                    val snapshot = stored.value?.takeIf { it.owner == owner }
                    mutable.value = mutable.value.copy(snapshot = snapshot, observed = true, snapshotReadable = true)
                    if (session is Session.SignedIn && snapshot != null) {
                        if (snapshot.syncedAt != null) {
                            messages.retireMangaChanges(owner) { id -> snapshot.entries.none { it.id == id } }
                        }
                        settleRecoveries(snapshot)
                    }
                }
                is Outcome.Failure -> {
                    if (stored.error == DataError.ListEnded) {
                        retire(null, signedIn = false)
                    } else {
                        mutable.value = mutable.value.copy(observed = true, snapshotReadable = false)
                    }
                }
            }
            stored
        }

    fun listRefreshed(snapshot: MangaListSnapshot) {
        val removals = removalRefreshes(snapshot.owner)
        scope.launch { publishRefresh(snapshot, removals) }
    }

    private fun removalRefreshes(owner: SignInId): List<Operation> =
        operations.values.filter { it.owner == owner && it.removing && it.phase == Phase.Refresh }

    private suspend fun publishRefresh(
        snapshot: MangaListSnapshot,
        removalRefreshes: List<Operation> = removalRefreshes(snapshot.owner),
    ): Outcome<MangaListSnapshot?> {
        val owner = snapshot.owner
        if (!active(owner)) return Outcome.Failure(DataError.ListEnded)
        if (snapshot.syncedAt != null) {
            removalRefreshes.forEach { op ->
                if (operations[op.id] === op) readyToRetry(op, snapshot)
            }
        }
        val stored = readSnapshot(owner)
        if (mutable.value.owner == owner && observerStopped) observation.value++
        return stored
    }

    suspend fun loadMembership(
        owner: SignInId,
        force: Boolean,
        isShowing: () -> Boolean,
    ): Outcome<MangaListSnapshot>? {
        val caller = currentCoroutineContext()
        if (!active(owner) || !isShowing()) return null
        if (!force && mutable.value.let { it.snapshotReadable && it.snapshot?.syncedAt != null }) return null
        val result = editor.refresh(owner) { caller.isActive && isShowing() && mutable.value.owner == owner && mutable.value.signedIn }
        if (!active(owner)) return null
        if (result !is Outcome.Success) return result
        return when (val stored = publishRefresh(result.value)) {
            is Outcome.Success -> stored.value?.let { Outcome.Success(it) } ?: Outcome.Failure(DataError.StorageError)
            is Outcome.Failure -> stored
        }.takeIf { active(owner) }
    }

    fun addPlanned(
        owner: SignInId,
        id: MangaId,
        title: String,
        totals: MangaEditTotals,
    ) {
        if (mutable.value.owner != owner || !mutable.value.canAdd(id)) return
        submitAdd(MangaChange(id, owner, title, totals))
    }

    private fun submitAdd(change: MangaChange) {
        val (id, owner) = change
        clearAddRecovery(id)
        mutable.value = mutable.value.copy(adding = mutable.value.adding + id)
        scope.launch {
            if (!active(owner)) return@launch
            val result = editor.addPlanned(owner, id)
            if (!active(owner)) return@launch
            if (result == MangaListEditResult.Failed(DataError.ListEnded)) {
                retire(null, signedIn = false)
                return@launch
            }
            val stored = readSnapshot(owner)
            if (!active(owner)) return@launch
            val snapshot = (stored as? Outcome.Success)?.value?.takeIf { it.syncedAt != null }
            val entry = snapshot?.entries?.find { it.id == id }
            val needsReadBack =
                when (result) {
                    is MangaListEditResult.Accepted ->
                        result.readBack is Outcome.Failure &&
                            result.readBack != Outcome.Failure(DataError.NotFound)
                    is MangaListEditResult.Uncertain -> result.needsRefresh
                    else -> false
                }
            when {
                result is MangaListEditResult.Confirmed -> finishAdd(change, added = true)
                result == MangaListEditResult.Discarded || result is MangaListEditResult.Accepted && result.readBack is Outcome.Success ->
                    finishAdd(change)
                entry != null -> finishAdd(change, added = needsReadBack && entry.isPlanned)
                result == MangaListEditResult.Failed(DataError.SessionExpired) -> {
                    finishAdd(change)
                    messages.post(ShellMessage(ShellMessage.Text.CouldNotSave, mangaOwner = owner))
                }
                else -> offerAddRecovery(change, readOnly = snapshot == null)
            }
        }
    }

    private fun offerAddRecovery(
        change: MangaChange,
        readOnly: Boolean,
    ) {
        val (id, owner) = change
        clearAddRecovery(id)
        lateinit var recovery: ShellMessage.Action.MangaRecovery
        recovery =
            ShellMessage.Action.MangaRecovery(readOnly) {
                if (additions[id]?.recovery === recovery && mutable.value.let { it.owner == owner && it.signedIn }) {
                    if (readOnly) {
                        reconcileAdd(change)
                    } else {
                        submitAdd(change)
                    }
                }
            }
        additions[id] = Addition(change, recovery)
        val state = mutable.value
        mutable.value =
            state.copy(adding = state.adding - id, reconcilingAdd = if (readOnly) state.reconcilingAdd + id else state.reconcilingAdd - id)
        val text = if (readOnly) ShellMessage.Text.MangaUnconfirmed else ShellMessage.Text.CouldNotSave
        messages.post(ShellMessage(text, recovery, mangaOwner = owner))
    }

    fun refreshAdd(
        owner: SignInId,
        id: MangaId,
    ) {
        val addition = additions[id]?.takeIf { it.change.owner == owner && it.recovery.readOnly } ?: return
        addition.recovery.run()
    }

    private fun reconcileAdd(change: MangaChange) {
        val (id, owner) = change
        clearAddRecovery(id)
        mutable.value = mutable.value.copy(adding = mutable.value.adding + id)
        scope.launch {
            if (!active(owner)) return@launch
            val result =
                when (val refreshed = editor.refresh(owner)) {
                    is Outcome.Success -> readSnapshot(owner)
                    else -> refreshed
                }
            if (!active(owner)) return@launch
            if (result == Outcome.Failure(DataError.ListEnded)) {
                retire(null, signedIn = false)
                return@launch
            }
            val snapshot = (result as? Outcome.Success)?.value?.takeIf { it.syncedAt != null }
            val entry = snapshot?.entries?.find { it.id == id }
            if (entry != null) {
                finishAdd(change, added = entry.isPlanned)
            } else {
                val current = mutable.value
                when {
                    current.snapshotReadable && current.snapshot?.entries?.any { it.id == id } == true -> finishAdd(change)
                    else ->
                        offerAddRecovery(
                            change,
                            readOnly =
                                snapshot == null || !current.snapshotReadable || current.snapshot?.syncedAt == null,
                        )
                }
            }
        }
    }

    private fun clearAddRecovery(id: MangaId) {
        additions.remove(id)?.recovery?.let(messages::remove)
    }

    private fun finishAdd(
        change: MangaChange,
        added: Boolean = false,
    ) {
        clearAddRecovery(change.id)
        val state = mutable.value
        mutable.value = state.copy(adding = state.adding - change.id, reconcilingAdd = state.reconcilingAdd - change.id)
        if (added) {
            val action = change.takeIf { state.snapshotReadable && state.snapshot?.entries?.any { it.id == change.id } == true }
            messages.post(ShellMessage(ShellMessage.Text.AddedToPlanned, action, mangaOwner = change.owner))
        }
    }

    private val MangaListEntry.isPlanned get() = status == MangaListStatus.PlanToRead.readingStatus

    fun dismissPresentation(instance: Long? = null) {
        if (instance == null || mutable.value.open?.instance == instance) mutable.value = mutable.value.copy(open = null)
    }

    fun open(
        owner: SignInId,
        id: MangaId,
        title: String,
        origin: MangaEditOrigin? = null,
        totals: MangaEditTotals = MangaEditTotals(),
    ) {
        if (!mutable.value.signedIn || mutable.value.owner != owner || mutable.value.open != null) return
        val entry =
            mutable.value.snapshot
                ?.entries
                ?.find { it.id == id } ?: return
        val operation = operations[id]
        val form =
            operation?.form?.at(title, totals) ?: MangaEditSheetState(title, entry.displayBaseline(), canRemove = true, totals = totals)
        mutable.value = mutable.value.copy(open = OpenMangaEditor(++instance, owner, operation?.baseline ?: entry, form, origin))
    }

    fun event(
        open: OpenMangaEditor,
        event: MangaEditSheetEvent,
    ) {
        val current = mutable.value.open?.takeIf { it.instance == open.instance && it.owner == mutable.value.owner } ?: return
        when (event) {
            MangaEditSheetEvent.Dismissed -> dismissPresentation()
            MangaEditSheetEvent.StartOver -> {
                val failed = current.form.submission as? MangaEditSubmission.Failed
                val op = operations[current.baseline.id]?.takeIf { failed?.canStartOver == true } ?: return
                operations.remove(op.id)
                op.action?.let(messages::remove)
                dismissPresentation()
                open(current.owner, op.id, current.form.title, current.origin, current.form.totals)
            }
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
                    event == MangaEditSheetEvent.Reconcile &&
                        op.phase in
                        setOf(
                            Phase.Refresh,
                            Phase.RefreshRetry,
                            Phase.RefreshAccepted,
                        )
                    -> recover(op)
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
        val open = state.open?.let { if (it.baseline.id == op.id) it.copy(form = op.form.at(it.form.title, it.form.totals)) else it }
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
                        DataError.ListEnded -> retire(null, signedIn = false)
                        else -> fromStore(writing)
                    }
                MangaListEditResult.RefreshRequired -> offer(writing, Phase.Refresh)
                MangaListEditResult.Discarded -> {
                    val stored = readSnapshot(writing.owner)
                    if (!active(writing.owner) || operations[writing.id] !== writing) return@launch
                    val snapshot = (stored as? Outcome.Success)?.value
                    val entry = snapshot?.entries?.find { it.id == writing.id }
                    val confirmed = snapshot?.syncedAt != null && entry != null && changes.isPresentIn(entry)
                    finish(writing, failed = !confirmed)
                }
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
                    DataError.ListEnded -> retire(null, signedIn = false)
                    else -> offer(op, Phase.Refresh)
                }
            is MangaListRemovalResult.ReadRequired, MangaListRemovalResult.RefreshRequired -> offer(op, Phase.Refresh)
            is MangaListRemovalResult.StillPresent -> if (result.canRetry) fromStore(op) else finish(op, failed = true)
            MangaListRemovalResult.Discarded -> {
                val result = readSnapshot(op.owner)
                if (!active(op.owner) || operations[op.id] !== op) return
                if (result == Outcome.Failure(DataError.ListEnded)) {
                    retire(null, signedIn = false)
                    return
                }
                val stored = (result as? Outcome.Success)?.value?.takeIf { it.owner == op.owner }
                val removed = stored?.syncedAt != null && stored.entries.none { it.id == op.id }
                finish(op, failed = !removed)
            }
        }
    }

    private suspend fun settle(op: Operation) {
        if (op.removing) messages.retireMangaChange(op.owner, op.id)
        val stored = readSnapshot(op.owner)
        if (!active(op.owner) || operations[op.id] !== op) return
        if (stored == Outcome.Failure(DataError.ListEnded)) {
            retire(null, signedIn = false)
            return
        }
        finish(op)
    }

    private suspend fun fromStore(op: Operation) {
        val stored = readSnapshot(op.owner)
        if (!active(op.owner) || operations[op.id] !== op) return
        if (stored == Outcome.Failure(DataError.ListEnded)) {
            retire(null, signedIn = false)
            return
        }
        val snapshot = (stored as? Outcome.Success)?.value?.takeIf { it.owner == op.owner }
        if (snapshot == null) {
            offer(op, Phase.Refresh)
        } else {
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
            snapshot.syncedAt == null -> offer(op, if (op.phase == Phase.Retry) Phase.RefreshRetry else Phase.Refresh)
            changes == null ->
                when {
                    entry == null -> finish(op)
                    !op.baseline.matchesRemovalRetry(entry) -> finish(op, failed = true)
                    op.phase != Phase.Retry -> offer(op, Phase.Retry)
                }
            entry != null && changes.isPresentIn(entry) -> finish(op)
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
            val result =
                if (op.phase == Phase.Retry) {
                    readSnapshot(op.owner)
                } else {
                    when (val refreshed = editor.refresh(op.owner)) {
                        is Outcome.Success -> readSnapshot(op.owner)
                        else -> refreshed
                    }
                }
            if (!active(op.owner) || operations[op.id] !== reading) return@launch
            if (result == Outcome.Failure(DataError.ListEnded)) {
                retire(null, signedIn = false)
                return@launch
            }
            if (result == Outcome.Failure(DataError.SessionExpired)) {
                offer(reading, op.phase.refreshPhase())
                return@launch
            }
            val snapshot = (result as? Outcome.Success)?.value?.takeIf { it.owner == op.owner }
            val entry = snapshot?.entries?.find { it.id == op.id }
            val changes = op.changes
            when {
                snapshot == null || snapshot.syncedAt == null -> offer(reading, op.phase.refreshPhase())
                changes == null ->
                    if (op.phase == Phase.Retry && entry != null && op.baseline.matchesRemovalRetry(entry)) {
                        write(reading, retry = true)
                    } else {
                        readyToRetry(reading, snapshot)
                    }
                op.phase == Phase.RefreshAccepted ->
                    if (acceptedSettled(op, snapshot, entry)) finish(reading) else offer(reading, Phase.RefreshAccepted)
                entry == null -> finish(reading, failed = true)
                changes.isPresentIn(entry) -> finish(reading)
                op.phase != Phase.Retry -> readyToRetry(reading, snapshot)
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

    private fun Phase.refreshPhase() = if (this == Phase.Retry) Phase.RefreshRetry else this

    private fun offer(
        op: Operation,
        phase: Phase,
    ) {
        val refresh = phase != Phase.Retry
        val text =
            when {
                op.removing && refresh -> ShellMessage.Text.MangaRemovalUnconfirmed
                op.removing -> ShellMessage.Text.MangaCouldNotRemove(op.form.title)
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
                removal = op.removing,
                canStartOver = phase != Phase.RefreshAccepted,
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
        if (op.removing && !failed) messages.retireMangaChange(op.owner, op.id)
        val state = mutable.value
        val open =
            state.open?.let {
                if (it.baseline.id != op.id) {
                    it
                } else {
                    val form =
                        if (failed) {
                            val label = if (op.removing) R.string.manga_edit_could_not_remove else R.string.shell_could_not_save
                            it.form.copy(submission = MangaEditSubmission.Failed(canRetry = false, message = label, removal = op.removing))
                        } else {
                            it.form.copy(closing = true)
                        }
                    it.copy(form = form)
                }
            }
        mutable.value = state.copy(pending = state.pending - op.id, open = open)
        if (failed && open?.baseline?.id != op.id) {
            val text = if (op.removing) ShellMessage.Text.MangaCouldNotRemove(op.form.title) else ShellMessage.Text.CouldNotSave
            messages.post(ShellMessage(text, mangaOwner = op.owner))
        }
    }
}

private fun MangaEditSheetState.showing(entry: MangaListEntry) =
    MangaEditSheetState(title, entry.displayBaseline(), canRemove = true, totals = totals)

private fun MangaEditSheetState.at(
    title: String,
    displayed: MangaEditTotals,
) = copy(title = title, totals = MangaEditTotals(displayed.chapters ?: totals.chapters, displayed.volumes ?: totals.volumes))

internal fun MangaListEntry.displayBaseline(): MangaEditBaseline =
    MangaEditBaseline(status, chaptersRead, volumesRead, score ?: 0.takeIf { explicitlyUnrated })

private fun MangaEditChanges.toEdit(): MangaListEdit =
    MangaListEdit(
        status = status?.let { choice -> MangaListStatus.entries.single { it.readingStatus == choice.readingStatus } },
        score = score,
        chaptersRead = chaptersRead,
        volumesRead = volumesRead,
    )
