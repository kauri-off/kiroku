package io.github.kauri_off.kiroku.edit

import android.text.format.DateFormat
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.window.DialogProperties
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.design.component.EpisodeStepperLayout
import io.github.kauri_off.kiroku.design.component.LineOrStack
import io.github.kauri_off.kiroku.design.component.TitleText
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.text.label
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
) = EditSheet(state, onEvent, focusCount = false)

// [focusCount] opens the sheet with the count field focused, for fixture states.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EditSheet(
    state: EditSheetState,
    onEvent: (EditSheetEvent) -> Unit,
    focusCount: Boolean,
) {
    val mayLeave by rememberUpdatedState(state.mayLeave)
    val events by rememberUpdatedState(onEvent)
    val sheetState =
        rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)) { value ->
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
    val countFocus = remember { FocusRequester() }
    val statusFocus = remember { FocusRequester() }
    var countSpan by remember { mutableStateOf(IntRange.EMPTY) }
    var countFocused by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // The scroll's own response to the keyboard can carry the field out of view; once it ends, the field comes back.
    LaunchedEffect(countFocused) {
        if (countFocused) {
            snapshotFlow { scroll.viewportSize }.collectLatest {
                awaitFrame()
                snapshotFlow { scroll.isScrollInProgress }.first { !it }
                scroll.scrollToShow(countSpan)
            }
        }
    }

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
        // A tap on another control ends typing, so the keyboard closes and Save shows again.
        val edit = { event: EditSheetEvent ->
            focusManager.clearFocus()
            onEvent(event)
        }
        Column(Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
                // The gaps between groups look equal once the controls' own invisible margins are counted.
                TitleText(state.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.l))
                StatusChoice(state.draft.status, Modifier.focusRequester(statusFocus)) { edit(EditSheetEvent.StatusChosen(it)) }
                Spacer(Modifier.height(Spacing.l))
                Column(
                    Modifier.onPlaced {
                        val top = it.positionInParent().y.roundToInt()
                        countSpan = top until top + it.size.height
                    },
                ) {
                    Label(stringResource(R.string.design_episodes_watched))
                    Spacer(Modifier.height(Spacing.xs))
                    EpisodeStepperLayout(
                        text = state.typed ?: state.draft.watchedEpisodes.toString(),
                        current = state.draft.watchedEpisodes,
                        total = state.total,
                        onTextChange = { onEvent(EditSheetEvent.WatchedTyped(it)) },
                        onDone = {
                            focusManager.clearFocus()
                            onEvent(EditSheetEvent.TypingEnded)
                        },
                        onFocusChange = {
                            countFocused = it
                            if (!it) onEvent(EditSheetEvent.TypingEnded)
                        },
                        onStep = { onEvent(EditSheetEvent.WatchedChanged(it)) },
                        modifier = Modifier.bleed(start = iconButtonMargin(R.drawable.ic_remove)),
                        fieldModifier = Modifier.focusRequester(countFocus),
                    )
                }
                // With key input the chips take the first focus, not the drag handle, whose tooltip would show unasked.
                val window = LocalWindowInfo.current
                val inputMode = LocalInputModeManager.current
                LaunchedEffect(Unit) {
                    if (focusCount) {
                        countFocus.requestFocus()
                    } else {
                        snapshotFlow { window.isWindowFocused }.first { it }
                        awaitFrame()
                        if (inputMode.inputMode == InputMode.Keyboard) statusFocus.requestFocus()
                    }
                }
                Spacer(Modifier.height(Spacing.xl))
                ScoreSlider(state.draft.score ?: 0) { edit(EditSheetEvent.ScoreChanged(it)) }
                Spacer(Modifier.height(Spacing.xl))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    DateInput(DateField.Start, state.draft.startDate, edit)
                    DateInput(DateField.Finish, state.draft.finishDate, edit)
                }
            }
            // Out of the scroll, so Save is on screen whenever the keyboard is closed.
            if (!WindowInsets.isImeVisible) {
                Spacer(Modifier.height(Spacing.xl))
                Actions(
                    remove =
                        if (state.isNew) {
                            null
                        } else {
                            {
                                TextButton(
                                    onClick = { onEvent(EditSheetEvent.RemoveClicked) },
                                ) { Text(stringResource(R.string.edit_remove)) }
                            }
                        },
                    save = {
                        Button(
                            onClick = { onEvent(EditSheetEvent.SaveClicked) },
                            shapes = ButtonDefaults.shapes(),
                            enabled = state.canSave,
                        ) {
                            Text(stringResource(R.string.edit_save))
                        }
                    },
                )
            }
        }
    }

    when (val dialog = state.dialog) {
        is EditDialog.PickDate -> DateDialog(dialog.field, state, onEvent)
        EditDialog.ConfirmRemove ->
            Confirmation(
                title = stringResource(R.string.edit_remove_title),
                text = stringResource(R.string.edit_remove_text),
                confirm = stringResource(R.string.edit_remove_confirm),
                dismiss = stringResource(R.string.edit_cancel),
                onConfirm = { onEvent(EditSheetEvent.RemoveConfirmed) },
                onDismiss = { onEvent(EditSheetEvent.DialogDismissed) },
            )
        EditDialog.ConfirmDiscard ->
            Confirmation(
                title = stringResource(R.string.edit_discard_title),
                text = null,
                confirm = stringResource(R.string.edit_discard_confirm),
                dismiss = stringResource(R.string.edit_keep_editing),
                onConfirm = { onEvent(EditSheetEvent.DiscardConfirmed) },
                onDismiss = { onEvent(EditSheetEvent.DialogDismissed) },
            )
        null -> Unit
    }
}

// A stock dialog that keeps the window's side margin however wide its text grows.
@Composable
private fun Confirmation(
    title: String,
    text: String?,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = text?.let { { Text(it) } },
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    properties = DialogProperties(usePlatformDefaultWidth = false),
    modifier = Modifier.padding(horizontal = Spacing.xl),
)

// Remove at the start and Save at the end of one line; when they do not fit, Save above Remove, both at the end.
// Remove's text, not its padding, lines up with the content edge.
@Composable
private fun Actions(
    remove: (@Composable () -> Unit)?,
    save: @Composable () -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val padding = ButtonDefaults.TextButtonContentPadding
    val startInset = padding.calculateStartPadding(direction)
    val endInset = padding.calculateEndPadding(direction)
    Layout(content = {
        save()
        remove?.invoke()
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val saveButton = measurables[0].measure(loose)
        val start = startInset.roundToPx()
        val end = endInset.roundToPx()
        val removeButton = measurables.getOrNull(1)?.measure(loose.copy(maxWidth = width + start + end))
        val gap = Spacing.s.roundToPx()
        val inline = removeButton == null || removeButton.width - start - end + gap + saveButton.width <= width
        if (inline) {
            val height = maxOf(saveButton.height, removeButton?.height ?: 0)
            layout(width, height) {
                removeButton?.placeRelative(-start, (height - removeButton.height) / 2)
                saveButton.placeRelative(width - saveButton.width, (height - saveButton.height) / 2)
            }
        } else {
            layout(width, saveButton.height + removeButton.height) {
                saveButton.placeRelative(width - saveButton.width, 0)
                removeButton.placeRelative(width - removeButton.width + end, saveButton.height)
            }
        }
    }
}

// One of five, as toggle buttons: the chosen one is filled and square, and choosing moves no button.
@Composable
private fun StatusChoice(
    status: ListStatus,
    modifier: Modifier,
    onChoose: (ListStatus) -> Unit,
) {
    FlowRow(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        ListStatus.entries.forEach { option ->
            val selected = option == status
            FilledTonalToggleButton(
                checked = selected,
                onCheckedChange = { if (!selected) onChoose(option) },
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(option.label()))
            }
        }
    }
}

// Read by the control it names, so a screen reader hears it once.
@Composable
private fun Label(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics {},
    )

// Scrolls as little as shows [span] of the content, its start first when it is taller than the viewport.
private suspend fun ScrollState.scrollToShow(span: IntRange) {
    if (span.isEmpty()) return
    val target = if (span.last + 1 - viewportSize > span.first) span.first else value.coerceIn(span.last + 1 - viewportSize, span.first)
    if (target != value) animateScrollTo(target)
}

// The space an icon button leaves around its icon, which [bleed] lets reach into the sheet's padding.
@Composable
private fun iconButtonMargin(icon: Int): Dp {
    val iconWidth = with(LocalDensity.current) { painterResource(icon).intrinsicSize.width.toDp() }
    return (LocalMinimumInteractiveComponentSize.current - iconWidth) / 2
}

// Lets a control's own padding reach into the sheet's, so its glyph lines up with the content edge.
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
    val slider = remember { SliderState(score.toFloat(), steps = MAX_SCORE - 1, trackRange = 0f..MAX_SCORE.toFloat()) }
    slider.value = score.toFloat()
    Slider(
        state = slider,
        onValueChange = { position -> position.roundToInt().let { if (it != score) onChange(it) } },
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
    val label = dateLabel(field)
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
        leadingIcon = { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null) },
        trailingIcon =
            date?.let {
                {
                    IconButton(onClick = { onEvent(EditSheetEvent.DateCleared(field)) }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = clear)
                    }
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

@Composable
private fun dateLabel(field: DateField): String =
    stringResource(if (field == DateField.Start) R.string.edit_start_date else R.string.edit_finish_date)

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
    ) {
        // Names the date being picked, in the stock title's place.
        DatePicker(
            picker,
            title = { Text(dateLabel(field), Modifier.padding(start = Spacing.xl, end = Spacing.m, top = Spacing.l)) },
        )
    }
}

private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private const val MAX_SCORE = 10
