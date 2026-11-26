package io.github.kauri_off.kiroku.recorder

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.data.manga.MangaMutationMethod
import io.github.kauri_off.kiroku.data.recorder.Halt
import io.github.kauri_off.kiroku.data.recorder.ListAfter
import io.github.kauri_off.kiroku.data.recorder.MANGA_CAPTURE_MINUTES
import io.github.kauri_off.kiroku.data.recorder.MangaCaptureState
import io.github.kauri_off.kiroku.data.recorder.MangaCaptureStop
import io.github.kauri_off.kiroku.data.recorder.MangaCaptureTarget
import io.github.kauri_off.kiroku.data.recorder.ReadCapture
import io.github.kauri_off.kiroku.data.recorder.RecordedTitle
import io.github.kauri_off.kiroku.data.recorder.Recording
import io.github.kauri_off.kiroku.data.recorder.RecordingRequests
import io.github.kauri_off.kiroku.debugmenu.SectionDetail
import io.github.kauri_off.kiroku.design.component.COUNTED_WAVY_INDICATOR
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.WAVY_INDICATOR
import io.github.kauri_off.kiroku.design.component.withoutAutofill
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.wiring.LiveGraph
import io.github.kauri_off.kiroku.wiring.appGraph

data class RecorderState(
    // Null on the demo, which has no account to record.
    val recording: Recording?,
    // The title's id as it is typed.
    val typed: String = "",
    val manga: MangaCaptureState? = null,
    val mangaId: String = "",
    val mangaSignedIn: Boolean = true,
)

sealed interface RecorderEvent {
    data class Type(
        val text: String,
    ) : RecorderEvent

    data class TypeManga(
        val text: String,
    ) : RecorderEvent

    data class ArmManga(
        val method: MangaMutationMethod,
    ) : RecorderEvent

    data class DisarmManga(
        val target: MangaCaptureTarget,
    ) : RecorderEvent

    data object Check : RecorderEvent

    data class Record(
        val title: RecordedTitle,
    ) : RecorderEvent

    data class RecordReads(
        val capture: ReadCapture,
    ) : RecorderEvent

    data object Dismiss : RecorderEvent

    data object Remove : RecorderEvent
}

private const val ID_DIGITS = 9

@Composable
fun RecorderRoute() {
    val graph = LocalContext.current.appGraph as? LiveGraph
    val recorder = graph?.recorder
    val recording = recorder?.state?.collectAsScreenState()?.value
    var typed by rememberSaveable { mutableStateOf("") }
    var mangaId by rememberSaveable { mutableStateOf("") }
    val manga =
        recorder
            ?.manga
            ?.state
            ?.collectAsScreenState()
            ?.value
    val session =
        remember(graph) { graph?.takeIf { recorder?.manga != null }?.session?.observeSession() }
            ?.collectAsScreenState(initial = null)
            ?.value
    RecorderSection(RecorderState(recording, typed, manga, mangaId, mangaSignedIn = session is Session.SignedIn)) { event ->
        when (event) {
            is RecorderEvent.Type -> typed = event.text.filter(Char::isDigit).take(ID_DIGITS)
            is RecorderEvent.TypeManga -> mangaId = event.text.filter { it in '0'..'9' }.take(ID_DIGITS)
            is RecorderEvent.ArmManga ->
                mangaId.toLongOrNull()?.let { value ->
                    recorder?.armManga(MangaId(value), event.method) { owner, id ->
                        val stored = graph.mangaList.stored(owner)
                        stored is Outcome.Success && stored.value?.entries?.any { it.id == id } == true
                    }
                }
            is RecorderEvent.DisarmManga -> recorder?.manga?.disarm(event.target)
            RecorderEvent.Check -> typed.toLongOrNull()?.let { recorder?.check(it) }
            is RecorderEvent.Record -> recorder?.record(event.title)
            is RecorderEvent.RecordReads -> recorder?.recordReads(event.capture)
            RecorderEvent.Dismiss -> recorder?.dismiss()
            RecorderEvent.Remove -> recorder?.remove()
        }
    }
}

@Composable
fun RecorderSection(
    state: RecorderState,
    onEvent: (RecorderEvent) -> Unit,
) {
    SectionHeader(stringResource(R.string.recorder_title))
    val recording = state.recording
    if (recording == null) {
        SectionDetail(R.string.recorder_unavailable)
        return
    }
    val mangaActive = state.manga is MangaCaptureState.Armed || state.manga is MangaCaptureState.Capturing
    val free = !recording.busy && !recording.left && !mangaActive
    SectionDetail(R.string.recorder_read_detail)
    ReadButton(R.string.recorder_suggestions, ReadCapture.Suggestions, free, onEvent)
    ReadButton(R.string.recorder_manga_list, ReadCapture.MangaList, free, onEvent)
    state.manga?.let { MangaCaptureSection(it, state.mangaId, state.mangaSignedIn && free && recording !is Recording.Checked, onEvent) }
    SectionDetail(R.string.recorder_detail)
    RecorderIdField(
        state.typed,
        free,
        R.string.recorder_title_id,
        supporting = { Text(stringResource(R.string.recorder_title_id_detail)) },
    ) { onEvent(RecorderEvent.Type(it)) }
    OutlinedButton(
        onClick = { onEvent(RecorderEvent.Check) },
        enabled = free && state.typed.isNotEmpty(),
        modifier = Modifier.padding(horizontal = Spacing.l),
    ) {
        Text(stringResource(R.string.recorder_check))
    }
    when (recording) {
        Recording.Idle -> Unit
        is Recording.Checking -> Progress(null, stringResource(R.string.recorder_checking))
        is Recording.Checked -> Confirmation(recording.title, onEvent)
        is Recording.Running ->
            Progress(
                recording.answers.toFloat() / recording.of,
                stringResource(R.string.recorder_running, recording.answers, recording.of),
            )
        is Recording.Removing -> Progress(null, stringResource(R.string.recorder_removing, recording.title.name))
        Recording.Reading -> Progress(null, stringResource(R.string.recorder_reading))
        else -> Report(report(recording), recording.left, onEvent)
    }
}

@Composable
private fun MangaCaptureSection(
    state: MangaCaptureState,
    id: String,
    enabled: Boolean,
    onEvent: (RecorderEvent) -> Unit,
) {
    SectionHeader(stringResource(R.string.recorder_manga_mutations))
    SectionDetail(R.string.recorder_manga_mutation_detail)
    when (state) {
        is MangaCaptureState.Armed -> {
            val label =
                if (state.target.method ==
                    MangaMutationMethod.Patch
                ) {
                    R.string.recorder_manga_armed_patch
                } else {
                    R.string.recorder_manga_armed_delete
                }
            MangaCaptureLine(stringResource(label, state.target.id.value))
            MangaCaptureLine(pluralStringResource(R.plurals.recorder_manga_window, MANGA_CAPTURE_MINUTES, MANGA_CAPTURE_MINUTES))
        }
        is MangaCaptureState.Capturing -> Progress(null, stringResource(R.string.recorder_manga_capturing, state.target.id.value))
        is MangaCaptureState.Complete -> {
            val label =
                if (state.target.method ==
                    MangaMutationMethod.Patch
                ) {
                    R.string.recorder_manga_patch_complete
                } else {
                    R.string.recorder_manga_delete_complete
                }
            MangaCaptureLine(stringResource(label, state.target.id.value))
            SelectionContainer { MangaCaptureLine(stringResource(R.string.recorder_folder, state.folder.path)) }
        }
        is MangaCaptureState.Stopped -> MangaCaptureLine(stringResource(state.reason.label()))
        MangaCaptureState.Ready -> Unit
    }
    val active =
        when (state) {
            is MangaCaptureState.Armed -> state.target
            is MangaCaptureState.Capturing -> state.target
            else -> null
        }
    if (active != null) {
        OutlinedButton(
            onClick = { onEvent(RecorderEvent.DisarmManga(active)) },
            modifier = Modifier.padding(horizontal = Spacing.l),
        ) {
            Text(stringResource(R.string.recorder_manga_disarm))
        }
    } else {
        RecorderIdField(id, enabled, R.string.recorder_manga_id) { onEvent(RecorderEvent.TypeManga(it)) }
        val canArm = enabled && (id.toLongOrNull() ?: 0) > 0
        for (method in MangaMutationMethod.entries) {
            val label = if (method == MangaMutationMethod.Patch) R.string.recorder_manga_arm_patch else R.string.recorder_manga_arm_delete
            OutlinedButton(
                onClick = { onEvent(RecorderEvent.ArmManga(method)) },
                enabled = canArm,
                modifier = Modifier.padding(horizontal = Spacing.l),
            ) {
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun RecorderIdField(
    id: String,
    enabled: Boolean,
    @StringRes label: Int,
    supporting: (@Composable () -> Unit)? = null,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = id,
        onValueChange = onValueChange,
        modifier = Modifier.withoutAutofill().fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        enabled = enabled,
        label = { Text(stringResource(label)) },
        supportingText = supporting,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    )
}

@Composable
private fun MangaCaptureLine(line: String) {
    Text(
        line,
        Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@StringRes
private fun MangaCaptureStop.label(): Int =
    when (this) {
        MangaCaptureStop.Unavailable -> R.string.recorder_halt_not_signed_in
        MangaCaptureStop.MissingEntry -> R.string.recorder_manga_missing
        MangaCaptureStop.Ended -> R.string.recorder_manga_ended
        MangaCaptureStop.Unconfirmed -> R.string.recorder_manga_unconfirmed
        MangaCaptureStop.Storage -> R.string.recorder_halt_storage
    }

private val Recording.busy: Boolean
    get() = this is Recording.Checking || this is Recording.Running || this is Recording.Removing || this == Recording.Reading

@Composable
private fun ReadButton(
    @StringRes label: Int,
    capture: ReadCapture,
    enabled: Boolean,
    onEvent: (RecorderEvent) -> Unit,
) {
    OutlinedButton(
        onClick = { onEvent(RecorderEvent.RecordReads(capture)) },
        enabled = enabled,
        modifier = Modifier.padding(horizontal = Spacing.l),
    ) {
        Text(stringResource(label))
    }
}

// The recorder's title may still be on the list: nothing new starts until it is off.
private val Recording.left: Boolean
    get() =
        when (this) {
            is Recording.Stopped -> list == ListAfter.Left
            is Recording.Leftover -> list == ListAfter.Left
            is Recording.Recorded -> list == ListAfter.Left
            else -> false
        }

@Composable
private fun Confirmation(
    title: RecordedTitle,
    onEvent: (RecorderEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(RecorderEvent.Dismiss) },
        title = { Text(stringResource(R.string.recorder_confirm_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(pluralStringResource(R.plurals.recorder_confirm_named, title.episodes, title.name, title.episodes))
                Text(stringResource(R.string.recorder_confirm_writes))
                Text(pluralStringResource(R.plurals.recorder_confirm_requests, RecordingRequests, RecordingRequests))
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(RecorderEvent.Record(title)) }) { Text(stringResource(R.string.recorder_record)) }
        },
        dismissButton = { TextButton(onClick = { onEvent(RecorderEvent.Dismiss) }) { Text(stringResource(R.string.recorder_cancel)) } },
    )
}

// Counted work shows how far it is; a wait of unknown length only that it runs.
@Composable
private fun Progress(
    done: Float?,
    line: String,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (done != null) {
            LinearWavyProgressIndicator(
                progress = { done.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().testTag(COUNTED_WAVY_INDICATOR),
            )
        } else {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth().testTag(WAVY_INDICATOR))
        }
        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// One sentence of a report; [alarming] when the user has something to do or to know that is not good news.
private class Line(
    val text: String,
    val alarming: Boolean = false,
)

@Composable
private fun Report(
    lines: List<Line>,
    left: Boolean,
    onEvent: (RecorderEvent) -> Unit,
) {
    // Selectable, so the folder and the address can be copied.
    SelectionContainer {
        Column(
            Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            for (line in lines) {
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (line.alarming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    OutlinedButton(
        onClick = { onEvent(if (left) RecorderEvent.Remove else RecorderEvent.Dismiss) },
        modifier = Modifier.padding(horizontal = Spacing.l),
    ) {
        Text(stringResource(if (left) R.string.recorder_remove_now else R.string.recorder_done))
    }
}

@Composable
private fun report(recording: Recording): List<Line> =
    when (recording) {
        is Recording.NotStarted -> listOf(Line(recording.halt.text(), alarming = true))
        is Recording.ReadRecorded ->
            listOf(
                Line(stringResource(R.string.recorder_recorded, recording.answers)),
                Line(stringResource(R.string.recorder_folder, recording.folder.path)),
            )
        is Recording.ReadStopped ->
            listOfNotNull(
                Line(stringResource(R.string.recorder_stopped, recording.at), alarming = true),
                Line(recording.halt.text(), alarming = true),
                Line(stringResource(R.string.recorder_stopped_answers, recording.answers)),
                recording.folder?.let { Line(stringResource(R.string.recorder_folder, it.path)) },
            )
        is Recording.Recorded ->
            listOf(
                Line(stringResource(R.string.recorder_recorded, recording.answers)),
                Line(stringResource(R.string.recorder_folder, recording.folder.path)),
            ) + list(recording.title, recording.list)
        is Recording.Stopped ->
            listOf(
                Line(stringResource(R.string.recorder_stopped, recording.at), alarming = true),
                Line(recording.halt.text(), alarming = true),
                Line(stringResource(R.string.recorder_stopped_answers, recording.answers)),
                Line(stringResource(R.string.recorder_folder, recording.folder.path)),
            ) + list(recording.title, recording.list)
        is Recording.Leftover ->
            listOfNotNull(recording.halt?.let { Line(it.text(), alarming = true) }) +
                if (recording.list == ListAfter.Left) {
                    list(recording.title, recording.list)
                } else {
                    listOf(Line(stringResource(R.string.recorder_leftover_removed, recording.title.name)), website(recording.title))
                }
        else -> emptyList()
    }

// What became of the user's list, in words that can be checked on the website.
@Composable
private fun list(
    title: RecordedTitle,
    list: ListAfter,
): List<Line> =
    when (list) {
        ListAfter.Untouched -> listOf(Line(stringResource(R.string.recorder_list_untouched)))
        ListAfter.Same -> listOf(Line(stringResource(R.string.recorder_list_same, title.name)), website(title))
        ListAfter.Removed -> listOf(Line(stringResource(R.string.recorder_list_removed, title.name)), website(title))
        ListAfter.Differs -> listOf(Line(stringResource(R.string.recorder_list_differs, title.name), alarming = true), website(title))
        ListAfter.Left -> listOf(Line(stringResource(R.string.recorder_list_left, title.name, title.id), alarming = true))
    }

@Composable
private fun website(title: RecordedTitle) = Line(stringResource(R.string.recorder_check_website, title.id))

@Composable
private fun Halt.text(): String =
    when (this) {
        Halt.OnTheList -> stringResource(R.string.recorder_halt_on_the_list)
        Halt.NotFinished -> stringResource(R.string.recorder_halt_not_finished)
        Halt.TooFewEpisodes -> stringResource(R.string.recorder_halt_too_few_episodes)
        Halt.NoSuchTitle -> stringResource(R.string.recorder_halt_no_such_title)
        Halt.NotSignedIn -> stringResource(R.string.recorder_halt_not_signed_in)
        Halt.NoAnswer -> stringResource(R.string.recorder_halt_no_answer)
        Halt.Storage -> stringResource(R.string.recorder_halt_storage)
        Halt.AnotherAccount -> stringResource(R.string.recorder_halt_another_account)
        Halt.StillOnTheList -> stringResource(R.string.recorder_halt_still_on_the_list)
        is Halt.Refused -> stringResource(R.string.recorder_halt_refused, status)
        is Halt.Unexpected -> stringResource(R.string.recorder_halt_unexpected, status)
    }
