package io.github.kauri_off.kiroku.edit

import android.text.format.DateFormat
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.design.component.EpisodeInput
import io.github.kauri_off.kiroku.design.component.EpisodeStepper
import io.github.kauri_off.kiroku.design.component.LineOrStack
import io.github.kauri_off.kiroku.design.component.episodeInput
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun EditSheet(
    state: EditSheetState,
    onEvent: (EditSheetEvent) -> Unit,
) = EditSheet(state, onEvent, typedEpisodes = null, focusEpisodes = false)

// [typedEpisodes] and [focusEpisodes] put the sheet in the middle of typing a count, for fixture states.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditSheet(
    state: EditSheetState,
    onEvent: (EditSheetEvent) -> Unit,
    typedEpisodes: String?,
    focusEpisodes: Boolean,
) {
    var typed by rememberSaveable { mutableStateOf(typedEpisodes) }
    val typedInput = typed?.let { episodeInput(it, state.draft.watchedEpisodes, state.total) }
    val unsaved = state.hasChanges || (typedInput != null && typedInput != EpisodeInput.Keep)
    val mayLeave by rememberUpdatedState(state.closing || !unsaved)
    val events by rememberUpdatedState(onEvent)
    val sheetState =
        rememberModalBottomSheetState(skipPartiallyExpanded = true) { value ->
            if (value != SheetValue.Hidden || mayLeave) {
                true
            } else {
                events(EditSheetEvent.DismissAsked)
                false
            }
        }
    LaunchedEffect(state.closing) {
        if (state.closing) {
            sheetState.hide()
            events(EditSheetEvent.Dismissed)
        }
    }
    val episodesFocus = remember { FocusRequester() }

    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = {
            if (mayLeave) {
                scope.launch { sheetState.hide() }.invokeOnCompletion { events(EditSheetEvent.Dismissed) }
            } else {
                events(EditSheetEvent.DismissAsked)
            }
        },
        sheetState = sheetState,
    ) {
        val focusManager = LocalFocusManager.current
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l),
        ) {
            // The gaps between groups look equal once the controls' own invisible margins are counted.
            StatusChips(state.draft.status) { onEvent(EditSheetEvent.StatusChosen(it)) }
            Spacer(Modifier.height(Spacing.l))
            Label(stringResource(R.string.edit_episodes))
            Spacer(Modifier.height(Spacing.xs))
            EpisodeStepper(
                watched = state.draft.watchedEpisodes,
                total = state.total,
                onWatchedChange = { onEvent(EditSheetEvent.WatchedChanged(it)) },
                modifier = Modifier.bleed(start = Spacing.m),
                typed = typedEpisodes,
                onTyping = { typed = it },
                fieldFocus = episodesFocus,
            )
            if (focusEpisodes) LaunchedEffect(Unit) { episodesFocus.requestFocus() }
            Spacer(Modifier.height(Spacing.xl))
            ScoreSlider(state.draft.score ?: 0) { onEvent(EditSheetEvent.ScoreChanged(it)) }
            Spacer(Modifier.height(Spacing.l + Spacing.xs))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                DateInput(DateField.Start, state.draft.startDate, onEvent)
                DateInput(DateField.Finish, state.draft.finishDate, onEvent)
            }
            Spacer(Modifier.height(Spacing.xl))
            LineOrStack(gap = Spacing.s, stackedEndInset = Spacing.s) {
                if (state.isNew) {
                    Box(Modifier)
                } else {
                    TextButton(onClick = { onEvent(EditSheetEvent.RemoveClicked) }, modifier = Modifier.bleed(start = Spacing.m)) {
                        Text(stringResource(R.string.edit_remove))
                    }
                }
                Button(
                    onClick = {
                        // Leaving the count field applies what was typed into it before the values are saved.
                        focusManager.clearFocus()
                        onEvent(EditSheetEvent.SaveClicked)
                    },
                    enabled = typedInput !is EpisodeInput.TooMany,
                ) { Text(stringResource(R.string.edit_save)) }
            }
        }
    }

    when (val dialog = state.dialog) {
        is EditDialog.PickDate -> DateDialog(dialog.field, state, onEvent)
        EditDialog.ConfirmRemove ->
            AlertDialog(
                onDismissRequest = { onEvent(EditSheetEvent.DialogDismissed) },
                title = { Text(stringResource(R.string.edit_remove_title)) },
                text = { Text(stringResource(R.string.edit_remove_text)) },
                confirmButton = {
                    TextButton(onClick = { onEvent(EditSheetEvent.RemoveConfirmed) }) { Text(stringResource(R.string.edit_remove_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { onEvent(EditSheetEvent.DialogDismissed) }) { Text(stringResource(R.string.edit_cancel)) }
                },
            )
        EditDialog.ConfirmDiscard ->
            AlertDialog(
                onDismissRequest = { onEvent(EditSheetEvent.DialogDismissed) },
                title = { Text(stringResource(R.string.edit_discard_title)) },
                confirmButton = {
                    TextButton(
                        onClick = { onEvent(EditSheetEvent.DiscardConfirmed) },
                    ) { Text(stringResource(R.string.edit_discard_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { onEvent(EditSheetEvent.DialogDismissed) }) { Text(stringResource(R.string.edit_keep_editing)) }
                },
            )
        null -> Unit
    }
}

@Composable
private fun StatusChips(
    status: ListStatus,
    onChoose: (ListStatus) -> Unit,
) {
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        ListStatus.entries.forEach { option ->
            val selected = option == status
            FilterChip(
                selected = selected,
                onClick = { if (!selected) onChoose(option) },
                label = { Text(statusName(option)) },
                leadingIcon =
                    if (selected) {
                        {
                            Icon(
                                painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun statusName(status: ListStatus): String =
    stringResource(
        when (status) {
            ListStatus.Watching -> R.string.edit_status_watching
            ListStatus.Planned -> R.string.edit_status_planned
            ListStatus.Completed -> R.string.edit_status_completed
            ListStatus.OnHold -> R.string.edit_status_on_hold
            ListStatus.Dropped -> R.string.edit_status_dropped
        },
    )

// Read by the control it names, so a screen reader hears it once.
@Composable
private fun Label(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics {},
    )

// Lets a control's own padding reach into the sheet's, so its glyph or text lines up with the content edge.
private fun Modifier.bleed(start: Dp): Modifier =
    layout { measurable, constraints ->
        val inset = start.roundToPx()
        val placeable = measurable.measure(constraints.offset(horizontal = inset))
        layout((placeable.width - inset).coerceAtLeast(0), placeable.height) { placeable.placeRelative(-inset, 0) }
    }

@Composable
private fun ScoreSlider(
    score: Int,
    onChange: (Int) -> Unit,
) {
    val label = stringResource(R.string.edit_score)
    val value = scoreText(score)
    LineOrStack(gap = Spacing.l, modifier = Modifier.clearAndSetSemantics {}) {
        Label(label)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
    Slider(
        value = score.toFloat(),
        onValueChange = { position -> position.roundToInt().let { if (it != score) onChange(it) } },
        valueRange = 0f..MAX_SCORE.toFloat(),
        steps = MAX_SCORE - 1,
        modifier =
            Modifier.semantics {
                contentDescription = label
                stateDescription = value
            },
    )
}

@Composable
private fun scoreText(score: Int): String {
    val name =
        stringResource(
            when (score) {
                1 -> R.string.edit_score_1
                2 -> R.string.edit_score_2
                3 -> R.string.edit_score_3
                4 -> R.string.edit_score_4
                5 -> R.string.edit_score_5
                6 -> R.string.edit_score_6
                7 -> R.string.edit_score_7
                8 -> R.string.edit_score_8
                9 -> R.string.edit_score_9
                10 -> R.string.edit_score_10
                else -> R.string.edit_score_0
            },
        )
    return if (score == 0) name else stringResource(R.string.edit_score_value, score, name)
}

@Composable
private fun DateInput(
    field: DateField,
    date: PartialDate?,
    onEvent: (EditSheetEvent) -> Unit,
) {
    val label = stringResource(if (field == DateField.Start) R.string.edit_start_date else R.string.edit_finish_date)
    val clear = stringResource(if (field == DateField.Start) R.string.edit_clear_start_date else R.string.edit_clear_finish_date)
    val choose = stringResource(R.string.edit_choose_date)
    val open = { onEvent(EditSheetEvent.DateOpened(field)) }
    val presses = remember { MutableInteractionSource() }
    LaunchedEffect(presses) {
        presses.interactions.collect { if (it is PressInteraction.Release) open() }
    }
    OutlinedTextField(
        value = date?.let { formatDate(it) } ?: "",
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
        trailingIcon = {
            if (date != null) {
                IconButton(onClick = { onEvent(EditSheetEvent.DateCleared(field)) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = clear)
                }
            } else {
                Icon(painterResource(R.drawable.ic_calendar), contentDescription = null)
            }
        },
        interactionSource = presses,
        modifier =
            Modifier.fillMaxWidth().semantics {
                onClick(choose) {
                    open()
                    true
                }
            },
    )
}

// A date as precise as MAL holds it, in the locale's own order.
@Composable
private fun formatDate(date: PartialDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (date) {
        is PartialDate.Day -> pattern(locale, notShown("dMMMy")).format(date.date)
        is PartialDate.Month -> pattern(locale, notShown("LLLLy")).format(date.yearMonth)
        is PartialDate.OnlyYear -> pattern(locale, notShown("y")).format(date.value)
    }
}

private fun pattern(
    locale: Locale,
    skeleton: String,
): DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(
    field: DateField,
    state: EditSheetState,
    onEvent: (EditSheetEvent) -> Unit,
) {
    val current = if (field == DateField.Start) state.draft.startDate else state.draft.finishDate
    val shown =
        when (current) {
            is PartialDate.Day -> current.date
            is PartialDate.Month -> current.yearMonth.atDay(1)
            is PartialDate.OnlyYear -> current.value.atDay(1)
            null -> state.today
        }
    val lastDay = state.today.utcMillis()
    val picker =
        rememberDatePickerState(
            initialSelectedDateMillis = (current as? PartialDate.Day)?.date?.utcMillis(),
            initialDisplayedMonthMillis = shown.withDayOfMonth(1).utcMillis(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= lastDay

                    override fun isSelectableYear(year: Int) = year <= state.today.year
                },
        )
    DatePickerDialog(
        onDismissRequest = { onEvent(EditSheetEvent.DialogDismissed) },
        confirmButton = {
            TextButton(
                onClick = { picker.selectedDateMillis?.let { onEvent(EditSheetEvent.DatePicked(field, it.utcDate())) } },
                enabled = picker.selectedDateMillis != null,
            ) { Text(stringResource(R.string.edit_ok)) }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(EditSheetEvent.DialogDismissed) }) { Text(stringResource(R.string.edit_cancel)) }
        },
    ) { DatePicker(picker) }
}

private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private const val MAX_SCORE = 10
