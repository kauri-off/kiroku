package io.github.kauri_off.kiroku.edit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.malCodeAsText
import io.github.kauri_off.kiroku.design.component.ConnectedChoice
import io.github.kauri_off.kiroku.design.component.CoversMessages
import io.github.kauri_off.kiroku.design.component.LineOrStack
import io.github.kauri_off.kiroku.design.component.TitleText
import io.github.kauri_off.kiroku.design.component.WaitIndicator
import io.github.kauri_off.kiroku.design.component.WindowWithoutAutofill
import io.github.kauri_off.kiroku.design.component.withoutAutofill
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.title.countText
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

@Composable
internal fun MangaEditSheet(
    state: MangaEditSheetState,
    onEvent: (MangaEditSheetEvent) -> Unit,
) = MangaEditSheet(state, focusVolumes = false, openScoreMenu = false, onEvent)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MangaEditSheet(
    state: MangaEditSheetState,
    focusVolumes: Boolean,
    openScoreMenu: Boolean,
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
        Column(
            Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                TitleText(state.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                MangaStatusChoice(state) { event ->
                    if (chaptersFocused || volumesFocused) focus.clearFocus()
                    onEvent(event)
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    MangaCountField(
                        text = state.chapters,
                        label = stringResource(R.string.manga_edit_chapters_read),
                        invalid = state.chaptersInvalid,
                        enabled = state.editable,
                        onChange = { onEvent(MangaEditSheetEvent.ChaptersTyped(it)) },
                        scroll = scroll,
                        onFocusChange = { chaptersFocused = it },
                    )
                    MangaCountField(
                        text = state.volumes,
                        label = stringResource(R.string.manga_edit_volumes_read),
                        invalid = state.volumesInvalid,
                        enabled = state.editable,
                        onChange = { onEvent(MangaEditSheetEvent.VolumesTyped(it)) },
                        scroll = scroll,
                        onFocusChange = { volumesFocused = it },
                        modifier = Modifier.focusRequester(volumeFocus),
                    )
                }
                MangaScoreChoice(state, openScoreMenu && sheetState.currentValue == SheetValue.Expanded) {
                    onEvent(MangaEditSheetEvent.ScoreChosen(it))
                }
            }
            if (!WindowInsets.isImeVisible) {
                MangaEditFooter(state, onEvent)
            }
        }
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
private fun MangaCountField(
    text: String,
    label: String,
    invalid: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
    scroll: ScrollState,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val bringIntoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, scroll) {
        if (focused) {
            snapshotFlow { scroll.viewportSize }.collectLatest {
                awaitFrame()
                snapshotFlow { scroll.isScrollInProgress }.first { !it }
                bringIntoView.bringIntoView()
            }
        }
    }
    val field =
        Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView).onFocusChanged {
            focused = it.isFocused
            onFocusChange(it.isFocused)
        }
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = modifier.withoutAutofill().then(field),
        enabled = enabled,
        label = { Text(label) },
        isError = invalid,
        supportingText =
            if (invalid) {
                { Text(stringResource(R.string.manga_edit_invalid_count, countText(0), countText(Int.MAX_VALUE))) }
            } else {
                null
            },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaScoreChoice(
    state: MangaEditSheetState,
    openScoreMenu: Boolean,
    onChoose: (Int?) -> Unit,
) {
    val enabled = state.editable
    var expanded by remember(enabled, openScoreMenu) { mutableStateOf(enabled && openScoreMenu) }
    val unknown = stringResource(R.string.manga_edit_unknown_score)
    val anchor = ExposedDropdownMenuAnchorType.PrimaryNotEditable
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = state.score?.let { scoreText(it) } ?: unknown,
            onValueChange = {},
            modifier = Modifier.withoutAutofill().menuAnchor(anchor, enabled).fillMaxWidth(),
            enabled = enabled,
            readOnly = true,
            label = { Text(stringResource(R.string.manga_edit_score)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val options = if (state.baseline.score == null) listOf(null, 0) + (10 downTo 1) else listOf(0) + (10 downTo 1)
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option?.let { scoreText(it) } ?: unknown) },
                    onClick = {
                        expanded = false
                        onChoose(option)
                    },
                )
            }
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
