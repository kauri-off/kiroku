package io.github.kauri_off.kiroku.notificationlog

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.notify.Recheck
import io.github.kauri_off.kiroku.core.notify.RunEnd
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.data.sandbox.Planted
import io.github.kauri_off.kiroku.debugmenu.SectionDetail
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.withoutAutofill
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.sandbox.SandboxGraph
import io.github.kauri_off.kiroku.text.currentLocale
import io.github.kauri_off.kiroku.text.skeletonFormat
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.DemoGraph
import io.github.kauri_off.kiroku.wiring.LiveGraph
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// What planting says after it was asked for, when it could not be done.
enum class PlantRefusal { NoneLeft, Aired }

// The sandbox's planted episode: the title it is of, the last one planted, and why the last request did nothing.
data class PlantState(
    val title: String,
    val planted: Planted?,
    val refusal: PlantRefusal? = null,
)

data class NotificationLogState(
    // Newest first.
    val runs: List<LoggedRun>,
    // How many of [runs] are shown.
    val shown: Int = PAGE,
    // Null off the sandbox, where nothing can be planted.
    val plant: PlantState? = null,
    // The minutes as they are typed.
    val minutes: String = DEFAULT_MINUTES,
)

sealed interface NotificationLogEvent {
    data class TypeMinutes(
        val text: String,
    ) : NotificationLogEvent

    data object Plant : NotificationLogEvent

    data object Postpone : NotificationLogEvent

    data object ShowEarlier : NotificationLogEvent
}

private const val PAGE = 30
private const val MINUTE_DIGITS = 4
private val DEFAULT_MINUTES = notShown("5")

// The log of the notification engine of the source the app runs on.
internal fun AppGraph.loggedRuns(): RunLog? =
    when (this) {
        is SandboxGraph -> runLog
        is LiveGraph -> runLog
        is DemoGraph -> runLog
        else -> null
    }

@Composable
fun NotificationLogRoute() {
    val graph = LocalContext.current.appGraph
    val runs =
        graph
            .loggedRuns()
            ?.runs
            ?.collectAsScreenState()
            ?.value
            .orEmpty()
    val sandbox = graph as? SandboxGraph
    var shown by rememberSaveable { mutableIntStateOf(PAGE) }
    var minutes by rememberSaveable { mutableStateOf(DEFAULT_MINUTES) }
    var plant by remember(graph) { mutableStateOf<PlantState?>(null) }
    // A reset of the sandbox forgets what was planted.
    val reset =
        sandbox
            ?.scenarios
            ?.reset
            ?.collectAsScreenState()
            ?.value
    LaunchedEffect(sandbox, reset) {
        if (sandbox != null) {
            plant =
                withContext(Dispatchers.IO) { sandbox.sandbox.plantedTitle?.let { PlantState(it, sandbox.sandbox.planted.lastOrNull()) } }
        }
    }
    val scope = rememberCoroutineScope()
    NotificationLogSection(NotificationLogState(runs, shown, plant, minutes)) { event ->
        when (event) {
            is NotificationLogEvent.TypeMinutes -> minutes = event.text.filter(Char::isDigit).take(MINUTE_DIGITS)
            NotificationLogEvent.ShowEarlier -> shown += PAGE
            NotificationLogEvent.Plant, NotificationLogEvent.Postpone -> {
                val by = Duration.ofMinutes(minutes.toLongOrNull() ?: return@NotificationLogSection)
                scope.launch {
                    val done = if (event == NotificationLogEvent.Plant) sandbox?.plant(by) else sandbox?.postpone(by)
                    val refusal = if (event == NotificationLogEvent.Plant) PlantRefusal.NoneLeft else PlantRefusal.Aired
                    plant = plant?.copy(planted = done ?: plant?.planted, refusal = refusal.takeIf { done == null })
                }
            }
        }
    }
}

// The runs of the notification engine, newest first, and on the sandbox an episode planted to air in a chosen number of minutes.
@Composable
fun NotificationLogSection(
    state: NotificationLogState,
    onEvent: (NotificationLogEvent) -> Unit,
) {
    SectionHeader(stringResource(R.string.notification_log_title))
    SectionDetail(if (state.runs.isEmpty()) R.string.notification_log_none else R.string.notification_log_detail)
    state.plant?.let { Planting(it, state.minutes, onEvent) }
    val names = remember(state.runs) { state.runs.flatMap { it.posted }.associate { it.anime to it.title } }
    // Selectable, so that runs can be copied into a report.
    SelectionContainer {
        Column {
            state.runs.take(state.shown).forEach { run -> Run(run, names) }
        }
    }
    if (state.runs.size > state.shown) {
        OutlinedButton(onClick = { onEvent(NotificationLogEvent.ShowEarlier) }, modifier = Modifier.padding(horizontal = Spacing.l)) {
            Text(stringResource(R.string.notification_log_earlier))
        }
    }
}

@Composable
private fun Planting(
    plant: PlantState,
    minutes: String,
    onEvent: (NotificationLogEvent) -> Unit,
) {
    Text(
        stringResource(R.string.notification_log_plant_detail, plant.title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, end = Spacing.l),
    )
    OutlinedTextField(
        value = minutes,
        onValueChange = { onEvent(NotificationLogEvent.TypeMinutes(it)) },
        modifier = Modifier.withoutAutofill().fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        label = { Text(stringResource(R.string.notification_log_minutes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    )
    val typed = (minutes.toLongOrNull() ?: 0) > 0
    FlowRow(Modifier.padding(horizontal = Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedButton(onClick = { onEvent(NotificationLogEvent.Plant) }, enabled = typed) {
            Text(stringResource(R.string.notification_log_plant))
        }
        OutlinedButton(onClick = { onEvent(NotificationLogEvent.Postpone) }, enabled = typed && plant.planted != null) {
            Text(stringResource(R.string.notification_log_postpone))
        }
    }
    plant.planted?.let {
        val line =
            if (it.at == it.plantedFor) {
                stringResource(R.string.notification_log_planted, it.episode, time(it.at))
            } else {
                stringResource(R.string.notification_log_planted_postponed, it.episode, time(it.at), time(it.plantedFor))
            }
        PlantLine(line, MaterialTheme.colorScheme.onSurface)
    }
    when (plant.refusal) {
        PlantRefusal.NoneLeft -> PlantLine(stringResource(R.string.notification_log_none_left), MaterialTheme.colorScheme.error)
        PlantRefusal.Aired -> PlantLine(stringResource(R.string.notification_log_aired), MaterialTheme.colorScheme.error)
        null -> Unit
    }
}

@Composable
private fun PlantLine(
    text: String,
    color: Color,
) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = color,
    modifier = Modifier.padding(start = Spacing.l, top = Spacing.s, end = Spacing.l),
)

@Composable
private fun Run(
    run: LoggedRun,
    names: Map<AnimeId, String>,
) {
    val lines =
        buildList {
            for (post in run.posted) {
                val text = if (post.shown) R.string.notification_log_posted else R.string.notification_log_posted_hidden
                add(stringResource(text, post.title, post.episode, lateness(post.delay)))
            }
            if (run.withdrawn.isNotEmpty()) {
                // A title no run of the log posted is named by its id.
                val titles =
                    run.withdrawn
                        .map { names[it] ?: stringResource(R.string.notification_log_title_id, it.value) }
                        .joinToString(stringResource(R.string.notification_log_between))
                add(stringResource(R.string.notification_log_withdrawn, titles))
            }
            recheck(run.recheck)?.let { add(it) }
            end(run.end)?.let { add(it) }
            add(
                when {
                    run.alarmFailed -> stringResource(R.string.notification_log_alarm_failed)
                    run.alarm != null -> stringResource(R.string.notification_log_alarm, time(run.alarm))
                    else -> stringResource(R.string.notification_log_no_alarm)
                },
            )
        }
    ListItem(
        overlineContent = {
            Text(stringResource(R.string.notification_log_when, time(run.began), duration(Duration.between(run.began, run.ended))))
        },
        content = { Text(stringResource(run.trigger.label)) },
        supportingContent = { Text(lines.joinToString(LINE)) },
    )
}

private val RunTrigger.label: Int
    get() =
        when (this) {
            RunTrigger.Alarm -> R.string.notification_log_trigger_alarm
            RunTrigger.DeviceRestarted -> R.string.notification_log_trigger_restarted
            RunTrigger.AppUpdated -> R.string.notification_log_trigger_updated
            RunTrigger.AlarmAccessChanged -> R.string.notification_log_trigger_access
            RunTrigger.AppOpened -> R.string.notification_log_trigger_opened
            RunTrigger.Periodic -> R.string.notification_log_trigger_periodic
            RunTrigger.MarkedWatched -> R.string.notification_log_trigger_marked
            RunTrigger.Changed -> R.string.notification_log_trigger_changed
        }

@Composable
private fun recheck(recheck: Recheck): String? =
    when (recheck) {
        Recheck.NotAsked -> null
        Recheck.Offline -> stringResource(R.string.notification_log_recheck_offline)
        Recheck.Answered -> stringResource(R.string.notification_log_recheck_answered)
        Recheck.Failed -> stringResource(R.string.notification_log_recheck_failed)
        Recheck.Late -> stringResource(R.string.notification_log_recheck_late)
    }

@Composable
private fun end(end: RunEnd): String? =
    when (end) {
        RunEnd.Done -> null
        RunEnd.SignedOut -> stringResource(R.string.notification_log_signed_out)
        RunEnd.Failed -> stringResource(R.string.notification_log_failed)
    }

// A delay from a notification's time to its post: before it, when the device's clock was set back between the two.
@Composable
private fun lateness(delay: Duration): String =
    if (delay.isNegative) {
        stringResource(R.string.notification_log_early, duration(delay.negated()))
    } else {
        stringResource(R.string.notification_log_late, duration(delay))
    }

// Seconds to a tenth under a minute, then minutes and seconds, then hours and minutes.
@Composable
private fun duration(duration: Duration): String {
    val seconds = duration.seconds
    return when {
        seconds < Duration.ofMinutes(1).seconds -> {
            val format = NumberFormat.getNumberInstance(currentLocale()).apply { maximumFractionDigits = 1 }
            stringResource(R.string.notification_log_seconds, format.format(duration.toMillis() / MILLIS_A_SECOND))
        }
        seconds < Duration.ofHours(1).seconds -> stringResource(R.string.notification_log_minutes_seconds, seconds / SIXTY, seconds % SIXTY)
        else -> stringResource(R.string.notification_log_hours_minutes, duration.toHours(), duration.toMinutes() % SIXTY)
    }
}

// The day and the time of day to the second, in the device's zone and 12/24-hour setting.
@Composable
private fun time(instant: Instant): String {
    val skeleton = if (DateFormat.is24HourFormat(LocalContext.current)) DAY_TIME_24 else DAY_TIME_12
    return skeletonFormat(currentLocale(), skeleton).format(instant.atZone(ZoneId.systemDefault()))
}

private const val MILLIS_A_SECOND = 1000.0
private const val SIXTY = 60
private val DAY_TIME_24 = notShown("dMMMyHms")
private val DAY_TIME_12 = notShown("dMMMyhms")
private val LINE = notShown("\n")
