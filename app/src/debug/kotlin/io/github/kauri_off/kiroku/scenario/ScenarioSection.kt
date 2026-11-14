package io.github.kauri_off.kiroku.scenario

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.debugmenu.SectionDetail
import io.github.kauri_off.kiroku.design.component.ConnectedChoice
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.theme.Spacing

sealed interface ScenarioState {
    // The app runs on the real account, where nothing is simulated.
    data object Unavailable : ScenarioState

    data class Ready(
        val scenario: Scenario,
        val reset: ResetState = ResetState.Idle,
    ) : ScenarioState
}

sealed interface ScenarioEvent {
    data class Change(
        val scenario: Scenario,
    ) : ScenarioEvent

    data object Reset : ScenarioEvent
}

@Composable
fun ScenarioRoute() {
    val scenarios = LocalContext.current.scenarios
    val state =
        if (scenarios == null) {
            ScenarioState.Unavailable
        } else {
            val scenario by scenarios.scenario.collectAsScreenState()
            val reset by scenarios.reset.collectAsScreenState()
            ScenarioState.Ready(scenario, reset)
        }
    ScenarioSection(state) { event ->
        when (event) {
            is ScenarioEvent.Change -> scenarios?.set(event.scenario)
            ScenarioEvent.Reset -> scenarios?.reset()
        }
    }
}

// Switches for the demo's failures, which apply at once, and a reset of its data.
@Composable
fun ScenarioSection(
    state: ScenarioState,
    onEvent: (ScenarioEvent) -> Unit,
) {
    SectionHeader(stringResource(R.string.scenarios_title))
    when (state) {
        ScenarioState.Unavailable -> SectionDetail(R.string.scenarios_unavailable)
        is ScenarioState.Ready -> Switches(state, onEvent)
    }
}

@Composable
private fun Switches(
    state: ScenarioState.Ready,
    onEvent: (ScenarioEvent) -> Unit,
) {
    val scenario = state.scenario
    val enabled = !state.reset.busy
    val change = { next: Scenario -> onEvent(ScenarioEvent.Change(next)) }
    SwitchRow(R.string.scenarios_offline, R.string.scenarios_offline_detail, scenario.offline, enabled) {
        change(scenario.copy(offline = it))
    }
    SwitchRow(R.string.design_session_expired, R.string.scenarios_session_detail, scenario.sessionExpired, enabled) {
        change(scenario.copy(sessionExpired = it))
    }
    SwitchRow(R.string.scenarios_slow, R.string.scenarios_slow_detail, scenario.slow, enabled) { change(scenario.copy(slow = it)) }
    Choice(
        R.string.scenarios_writes,
        R.string.scenarios_writes_detail,
        WriteFault.entries.map { it.label },
        scenario.writes.ordinal,
        enabled,
    ) { change(scenario.copy(writes = WriteFault.entries[it])) }
    Choice(
        R.string.scenarios_store,
        R.string.scenarios_store_detail,
        StoreFault.entries.map { it.label },
        scenario.store.ordinal,
        enabled,
    ) { change(scenario.copy(store = StoreFault.entries[it])) }
    SectionHeader(stringResource(R.string.scenarios_reset_title), Modifier.padding(top = Spacing.xl))
    SectionDetail(R.string.scenarios_reset_detail)
    OutlinedButton(
        onClick = { onEvent(ScenarioEvent.Reset) },
        enabled = enabled,
        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        Text(stringResource(R.string.scenarios_reset))
    }
    state.reset.message?.let { ResetMessage(it, failed = state.reset == ResetState.Failed) }
}

// Said where Reset was touched, so it is never below the fold.
@Composable
private fun ResetMessage(
    @StringRes message: Int,
    failed: Boolean,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(message) { requester.bringIntoView() }
    Text(
        stringResource(message),
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .padding(horizontal = Spacing.l)
                .bringIntoViewRequester(requester)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SwitchRow(
    @StringRes label: Int,
    @StringRes detail: Int,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) = ListItem(
    modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    supportingContent = { Text(stringResource(detail)) },
    trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    content = { Text(stringResource(label)) },
)

@Composable
private fun Choice(
    @StringRes title: Int,
    @StringRes detail: Int,
    labels: List<Int>,
    chosen: Int,
    enabled: Boolean,
    onChoose: (Int) -> Unit,
) {
    SectionHeader(stringResource(title), Modifier.padding(top = Spacing.xl))
    SectionDetail(detail)
    ConnectedChoice(
        labels.map { stringResource(it) },
        chosen,
        onChoose,
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        enabled,
    )
}

private val ResetState.message: Int?
    get() =
        when (this) {
            ResetState.Idle -> null
            ResetState.WaitingForSave -> R.string.scenarios_reset_waiting
            ResetState.Running -> R.string.scenarios_reset_running
            ResetState.SaveDropped -> R.string.scenarios_reset_dropped
            ResetState.Failed -> R.string.scenarios_reset_failed
        }

private val WriteFault.label: Int
    get() =
        when (this) {
            WriteFault.None -> R.string.scenarios_writes_none
            WriteFault.Refused -> R.string.scenarios_writes_refused
            WriteFault.ServerError -> R.string.scenarios_writes_server_error
        }

private val StoreFault.label: Int
    get() =
        when (this) {
            StoreFault.None -> R.string.scenarios_store_none
            StoreFault.WritesFail -> R.string.scenarios_store_writes_fail
            StoreFault.Unreadable -> R.string.scenarios_store_unreadable
        }
