package io.github.kauri_off.kiroku.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MangaEditSheet(
    state: MangaEditSheetState,
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
        Column(
            Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                TitleText(state.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                MangaStatusChoice(state) {
                    focus.clearFocus()
                    onEvent(MangaEditSheetEvent.StatusChosen(it))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    MangaCountField(
                        text = state.chapters,
                        label = stringResource(R.string.manga_edit_chapters_read),
                        invalid = state.chaptersInvalid,
                        enabled = state.editable,
                        onChange = { onEvent(MangaEditSheetEvent.ChaptersTyped(it)) },
                    )
                    MangaCountField(
                        text = state.volumes,
                        label = stringResource(R.string.manga_edit_volumes_read),
                        invalid = state.volumesInvalid,
                        enabled = state.editable,
                        onChange = { onEvent(MangaEditSheetEvent.VolumesTyped(it)) },
                    )
                }
                MangaScoreChoice(state.score, state.editable) { onEvent(MangaEditSheetEvent.ScoreChosen(it)) }
            }
            if (!WindowInsets.isImeVisible) {
                MangaEditFooter(state, onEvent)
            }
        }
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
    onChoose: (MangaEditStatus) -> Unit,
) {
    val statuses = MangaEditStatus.entries
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (state.status == null) {
            Text(
                stringResource(R.string.manga_edit_current_status, malCodeAsText(state.baseline.status.code)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ConnectedChoice(
            labels = statuses.map { stringResource(it.label) },
            chosen = statuses.indexOfFirst { it == state.status },
            onChoose = { onChoose(statuses[it]) },
            enabled = state.editable,
        )
    }
}

@Composable
private fun MangaCountField(
    text: String,
    label: String,
    invalid: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier.withoutAutofill().fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        isError = invalid,
        supportingText =
            if (invalid) {
                { Text(stringResource(R.string.manga_edit_invalid_count)) }
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
    score: Int?,
    enabled: Boolean,
    onChoose: (Int) -> Unit,
) {
    var expanded by remember(enabled) { mutableStateOf(false) }
    val anchor = ExposedDropdownMenuAnchorType.PrimaryNotEditable
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = score?.let { scoreText(it) } ?: stringResource(R.string.manga_edit_unknown_score),
            onValueChange = {},
            modifier = Modifier.withoutAutofill().menuAnchor(anchor, enabled).fillMaxWidth(),
            enabled = enabled,
            readOnly = true,
            label = { Text(stringResource(R.string.manga_edit_score)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val options = listOf(0) + (10 downTo 1)
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(scoreText(option)) },
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
            EditActions(remove = null) {
                Button(
                    onClick = { changes?.let { onEvent(MangaEditSheetEvent.Save(it)) } },
                    shapes = ButtonDefaults.shapes(),
                    enabled = changes != null,
                ) {
                    Text(stringResource(R.string.edit_save))
                }
            }
        }
        MangaEditSubmission.Saving ->
            LineOrStack(
                gap = Spacing.s,
                centred = true,
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(stringResource(R.string.manga_edit_saving))
                WaitIndicator()
            }
        is MangaEditSubmission.Failed ->
            LineOrStack(gap = Spacing.s, centred = true) {
                Text(
                    stringResource(submission.message),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
                )
                if (submission.canRetry) {
                    Button(onClick = { onEvent(MangaEditSheetEvent.Retry) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.design_retry))
                    }
                }
            }
    }
}
