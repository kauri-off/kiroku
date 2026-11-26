package io.github.kauri_off.kiroku.edit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.malCodeAsText
import io.github.kauri_off.kiroku.design.component.ConnectedChoice
import io.github.kauri_off.kiroku.design.component.CoversMessages
import io.github.kauri_off.kiroku.design.component.LineOrStack
import io.github.kauri_off.kiroku.design.component.ProgressStepperLabels
import io.github.kauri_off.kiroku.design.component.ProgressStepperLayout
import io.github.kauri_off.kiroku.design.component.TitleText
import io.github.kauri_off.kiroku.design.component.WaitIndicator
import io.github.kauri_off.kiroku.design.component.WindowWithoutAutofill
import io.github.kauri_off.kiroku.design.component.stepButtonMargin
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.title.countText
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

@Composable
internal fun MangaEditSheet(
    state: MangaEditSheetState,
    onEvent: (MangaEditSheetEvent) -> Unit,
) = MangaEditSheet(state, focusVolumes = false, onEvent)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MangaEditSheet(
    state: MangaEditSheetState,
    focusVolumes: Boolean,
    onEvent: (MangaEditSheetEvent) -> Unit,
) {
    CoversMessages()
    val mayLeave by rememberUpdatedState(state.mayLeave)
    val events by rememberUpdatedState(onEvent)
    val sheetState =
        rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)) { value ->
            if (value != SheetValue.Hidden || mayLeave) {
                true
            } else {
                events(MangaEditSheetEvent.DismissAsked)
                false
            }
        }
    LaunchedEffect(state.closing) {
        if (state.closing) {
            sheetState.hide()
            events(MangaEditSheetEvent.Dismissed)
        }
    }

    ModalBottomSheet(
        onDismissRequest = { events(if (mayLeave) MangaEditSheetEvent.Dismissed else MangaEditSheetEvent.DismissAsked) },
        sheetState = sheetState,
    ) {
        WindowWithoutAutofill()
        val focus = LocalFocusManager.current
        var chaptersFocused by remember { mutableStateOf(false) }
        var volumesFocused by remember { mutableStateOf(false) }
        val volumeFocus = remember { FocusRequester() }
        val scroll = rememberScrollState()
        LaunchedEffect(focusVolumes, sheetState.currentValue) {
            if (focusVolumes && sheetState.currentValue == SheetValue.Expanded) volumeFocus.requestFocus()
        }
        val statusFocus = remember { FocusRequester() }
        val edit = { event: MangaEditSheetEvent ->
            if (chaptersFocused || volumesFocused) focus.clearFocus()
            onEvent(event)
        }
        EditSheetContent(
            scroll,
            content = {
                TitleText(state.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.l))
                MangaStatusChoice(state, Modifier.focusRequester(statusFocus), edit)
                Spacer(Modifier.height(Spacing.l))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    MangaCountStepper(
                        state.chapters,
                        state.totals.chapters,
                        ProgressStepperLabels.Chapters,
                        state.chaptersInvalid,
                        state.editable,
                        unknown = state.baseline.chaptersRead == null,
                        onChange = { onEvent(MangaEditSheetEvent.ChaptersTyped(it)) },
                        onStep = { edit(MangaEditSheetEvent.ChaptersTyped(it.toString())) },
                        scroll = scroll,
                        onFocusChange = { chaptersFocused = it },
                    )
                    MangaCountStepper(
                        state.volumes,
                        state.totals.volumes,
                        ProgressStepperLabels.Volumes,
                        state.volumesInvalid,
                        state.editable,
                        unknown = state.baseline.volumesRead == null,
                        onChange = { onEvent(MangaEditSheetEvent.VolumesTyped(it)) },
                        onStep = { edit(MangaEditSheetEvent.VolumesTyped(it.toString())) },
                        scroll = scroll,
                        onFocusChange = { volumesFocused = it },
                        fieldModifier = Modifier.focusRequester(volumeFocus),
                    )
                }
                if (state.editable && !focusVolumes) EditInitialFocus(statusFocus)
                Spacer(Modifier.height(Spacing.xl))
                ScoreSlider(
                    score = state.score ?: UNKNOWN_SCORE_POSITION,
                    enabled = state.editable,
                    unknownLabel = if (state.baseline.score == null) stringResource(R.string.manga_edit_unknown) else null,
                ) { score ->
                    edit(MangaEditSheetEvent.ScoreChosen(score.takeUnless { it == UNKNOWN_SCORE_POSITION }))
                }
            },
            footer = { MangaEditFooter(state, onEvent) },
        )
    }

    if (state.confirmRemove) {
        EditConfirmation(
            title = stringResource(R.string.manga_edit_remove_title, state.title),
            text = stringResource(R.string.manga_edit_remove_text),
            confirm = stringResource(R.string.edit_remove_confirm),
            dismiss = stringResource(R.string.edit_cancel),
            onConfirm = { onEvent(MangaEditSheetEvent.RemoveConfirmed) },
            onDismiss = { onEvent(MangaEditSheetEvent.CancelRemove) },
        )
    }

    if (state.confirmDiscard) {
        EditConfirmation(
            title = stringResource(R.string.edit_discard_title),
            text = null,
            confirm = stringResource(R.string.edit_discard_confirm),
            dismiss = stringResource(R.string.edit_keep_editing),
            onConfirm = { onEvent(MangaEditSheetEvent.Discard) },
            onDismiss = { onEvent(MangaEditSheetEvent.KeepEditing) },
        )
    }
}

@Composable
private fun MangaStatusChoice(
    state: MangaEditSheetState,
    modifier: Modifier,
    onEvent: (MangaEditSheetEvent) -> Unit,
) {
    val statuses = MangaEditStatus.entries
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        val submitted = state.submission != MangaEditSubmission.Editing
        if (state.status == null || submitted) {
            val status = state.status?.let { stringResource(it.label) } ?: malCodeAsText(state.baseline.status.code)
            val removal =
                state.submission == MangaEditSubmission.Removing ||
                    state.submission == MangaEditSubmission.CheckingRemoval ||
                    (state.submission as? MangaEditSubmission.Failed)?.removal == true
            val label = if (submitted && !removal) R.string.manga_edit_submitted_status else R.string.manga_edit_current_status
            Text(
                stringResource(label, status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!submitted) {
            ConnectedChoice(
                labels = statuses.map { stringResource(it.label) },
                chosen = statuses.indexOfFirst { it == state.status },
                onChoose = { onEvent(MangaEditSheetEvent.StatusChosen(statuses[it])) },
                enabled = state.editable,
                modifier = modifier,
            )
            if (state.canRestoreStatus) {
                TextButton(onClick = { onEvent(MangaEditSheetEvent.RestoreStatus) }, enabled = state.editable) {
                    Text(stringResource(R.string.manga_edit_restore_status))
                }
            }
        }
    }
}

@Composable
private fun MangaCountStepper(
    text: String,
    total: Int?,
    labels: ProgressStepperLabels,
    invalid: Boolean,
    enabled: Boolean,
    unknown: Boolean,
    onChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    scroll: ScrollState,
    onFocusChange: (Boolean) -> Unit,
    fieldModifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val bringIntoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused) {
            snapshotFlow { scroll.viewportSize }.collectLatest {
                awaitFrame()
                snapshotFlow { scroll.isScrollInProgress }.first { !it }
                bringIntoView.bringIntoView()
            }
        }
    }
    Column(Modifier.bringIntoViewRequester(bringIntoView)) {
        EditLabel(stringResource(labels.count))
        Spacer(Modifier.height(Spacing.xs))
        ProgressStepperLayout(
            text = text,
            current = text.toIntOrNull(),
            total = total,
            onTextChange = onChange,
            onDone = { focus.clearFocus() },
            onFocusChange = {
                focused = it
                onFocusChange(it)
            },
            onStep = onStep,
            modifier = Modifier.bleed(start = stepButtonMargin),
            enabled = enabled,
            fieldModifier = fieldModifier,
            labels = labels,
            maximum = Int.MAX_VALUE,
            unknown = if (unknown) stringResource(R.string.manga_edit_unknown) else null,
            invalid = invalid,
            acceptText = { it },
        )
        if (invalid) {
            Text(
                stringResource(R.string.manga_edit_invalid_count, countText(0), countText(Int.MAX_VALUE)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MangaEditFooter(
    state: MangaEditSheetState,
    onEvent: (MangaEditSheetEvent) -> Unit,
) {
    when (val submission = state.submission) {
        MangaEditSubmission.Editing -> {
            val changes = state.saveChanges
            val remove: (@Composable () -> Unit)? =
                if (state.canRemove) {
                    {
                        TextButton(onClick = { onEvent(MangaEditSheetEvent.RemoveAsked) }, enabled = state.editable) {
                            Text(stringResource(R.string.edit_remove))
                        }
                    }
                } else {
                    null
                }
            EditActions(remove = remove) {
                Button(
                    onClick = { changes?.let { onEvent(MangaEditSheetEvent.Save(it)) } },
                    shapes = ButtonDefaults.shapes(),
                    enabled = changes != null,
                ) {
                    Text(stringResource(R.string.edit_save))
                }
            }
        }
        MangaEditSubmission.Saving, MangaEditSubmission.Reconciling, MangaEditSubmission.Removing, MangaEditSubmission.CheckingRemoval ->
            LineOrStack(
                gap = Spacing.s,
                centred = true,
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                val label =
                    when (submission) {
                        MangaEditSubmission.Removing -> R.string.manga_edit_removing
                        MangaEditSubmission.CheckingRemoval -> R.string.manga_edit_checking_removal
                        MangaEditSubmission.Reconciling -> R.string.manga_edit_checking_save
                        else -> R.string.manga_edit_saving
                    }
                Text(stringResource(label))
                WaitIndicator()
            }
        is MangaEditSubmission.Failed ->
            Column {
                LineOrStack(gap = Spacing.s, centred = true) {
                    Text(
                        stringResource(submission.message),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (submission.canRetry || submission.canReconcile) {
                        Button(
                            onClick = { onEvent(if (submission.canRetry) MangaEditSheetEvent.Retry else MangaEditSheetEvent.Reconcile) },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(if (submission.canRetry) R.string.design_retry else R.string.manga_edit_refresh_list))
                        }
                    }
                }
                if (submission.canStartOver) {
                    TextButton(onClick = { onEvent(MangaEditSheetEvent.StartOver) }) {
                        Text(stringResource(R.string.manga_edit_start_over))
                    }
                }
            }
    }
}
