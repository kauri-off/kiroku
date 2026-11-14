package io.github.kauri_off.kiroku.source

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.debugmenu.SectionDetail
import io.github.kauri_off.kiroku.design.component.ConnectedChoice
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.theme.Spacing

data class SourceState(
    val running: DataSource,
    // What the next start will be on.
    val chosen: DataSource,
    val kept: Boolean = true,
)

sealed interface SourceEvent {
    data class Choose(
        val source: DataSource,
    ) : SourceEvent

    data object Restart : SourceEvent
}

@Composable
fun SourceRoute(restart: (Context) -> Unit = ::restartApp) {
    val context = LocalContext.current
    val choice = remember { SourceChoice(context) }
    var state by remember { mutableStateOf(SourceState(context.runningSource, choice.startsOn(BuildConfig.MAL_CLIENT_ID))) }
    SourceSection(state) { event ->
        when (event) {
            is SourceEvent.Choose -> {
                val kept = choice.choose(event.source)
                state = state.copy(chosen = choice.startsOn(BuildConfig.MAL_CLIENT_ID), kept = kept)
            }
            SourceEvent.Restart -> restart(context)
        }
    }
}

// The source the app starts on. A choice is kept at once and holds from the next start, which the button brings on.
@Composable
fun SourceSection(
    state: SourceState,
    onEvent: (SourceEvent) -> Unit,
) {
    SectionHeader(stringResource(R.string.source_title))
    SectionDetail(R.string.source_detail)
    ConnectedChoice(
        DataSource.entries.map { stringResource(it.label) },
        state.chosen.ordinal,
        { onEvent(SourceEvent.Choose(DataSource.entries[it])) },
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
    )
    if (!state.kept) {
        Text(
            stringResource(R.string.source_not_kept),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = Spacing.l).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.chosen != state.running) {
        SectionDetail(state.running.stillRunning)
        OutlinedButton(
            onClick = { onEvent(SourceEvent.Restart) },
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            Text(stringResource(R.string.source_restart))
        }
    }
}

private val DataSource.label: Int
    get() =
        when (this) {
            DataSource.Account -> R.string.source_account
            DataSource.Demo -> R.string.source_demo
        }

private val DataSource.stillRunning: Int
    get() =
        when (this) {
            DataSource.Account -> R.string.source_running_account
            DataSource.Demo -> R.string.source_running_demo
        }

// The app's first screen in a task of its own, as a tap on its icon gives.
fun relaunchIntent(context: Context): Intent =
    checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

// Ends this process; the system starts a new one for the screen asked for just before.
fun restartApp(context: Context) {
    context.startActivity(relaunchIntent(context))
    Runtime.getRuntime().exit(0)
}
