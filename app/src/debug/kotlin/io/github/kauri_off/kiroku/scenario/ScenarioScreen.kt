package io.github.kauri_off.kiroku.scenario

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.edit.balancedRows

sealed interface ScenarioState {
    // The app runs on a real account, where nothing is simulated.
    data object Unavailable : ScenarioState

    data class Ready(
        val scenario: Scenario,
        val resetting: Boolean = false,
    ) : ScenarioState
}

sealed interface ScenarioEvent {
    data class Change(
        val scenario: Scenario,
    ) : ScenarioEvent

    data object Reset : ScenarioEvent

    data object Close : ScenarioEvent
}

// The debug panel: switches for the demo's failures, which apply at once, and a reset of its data.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioScreen(
    state: ScenarioState,
    onEvent: (ScenarioEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scenarios_title)) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(ScenarioEvent.Close) }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            ScenarioState.Unavailable ->
                ScreenState(ScreenState.Empty(stringResource(R.string.scenarios_unavailable)), Modifier.padding(padding))
            is ScenarioState.Ready ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    Switches(state, onEvent, Modifier.widthIn(max = Sizes.contentMaxWidth))
                }
        }
    }
}

@Composable
private fun Switches(
    state: ScenarioState.Ready,
    onEvent: (ScenarioEvent) -> Unit,
    modifier: Modifier,
) {
    val scenario = state.scenario
    val change = { next: Scenario -> onEvent(ScenarioEvent.Change(next)) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl)) {
        SwitchRow(R.string.scenarios_offline, R.string.scenarios_offline_detail, scenario.offline) {
            change(scenario.copy(offline = it))
        }
        SwitchRow(R.string.design_session_expired, R.string.scenarios_session_detail, scenario.sessionExpired) {
            change(scenario.copy(sessionExpired = it))
        }
        SwitchRow(R.string.scenarios_slow, R.string.scenarios_slow_detail, scenario.slow) { change(scenario.copy(slow = it)) }
        Choice(
            R.string.scenarios_writes,
            R.string.scenarios_writes_detail,
            WriteFault.entries.map { it.label },
            scenario.writes.ordinal,
        ) { change(scenario.copy(writes = WriteFault.entries[it])) }
        Choice(
            R.string.scenarios_store,
            R.string.scenarios_store_detail,
            StoreFault.entries.map { it.label },
            scenario.store.ordinal,
        ) { change(scenario.copy(store = StoreFault.entries[it])) }
        SectionHeader(stringResource(R.string.scenarios_reset_title), Modifier.padding(top = Spacing.xl))
        Detail(R.string.scenarios_reset_detail)
        OutlinedButton(
            onClick = { onEvent(ScenarioEvent.Reset) },
            enabled = !state.resetting,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            Text(stringResource(R.string.scenarios_reset))
        }
    }
}

@Composable
private fun SwitchRow(
    @StringRes label: Int,
    @StringRes detail: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) = ListItem(
    modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    supportingContent = { Text(stringResource(detail)) },
    trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    content = { Text(stringResource(label)) },
)

// One of a few options as a connected button group; the chosen one differs in colour and shape.
@Composable
private fun Choice(
    @StringRes title: Int,
    @StringRes detail: Int,
    labels: List<Int>,
    chosen: Int,
    onChoose: (Int) -> Unit,
) {
    SectionHeader(stringResource(title), Modifier.padding(top = Spacing.xl))
    Detail(detail)
    val texts = labels.map { stringResource(it) }
    val widths = toggleButtonWidths(texts)
    val gap = ButtonGroupDefaults.ConnectedSpaceBetween
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        val rows = balancedRows(widths, maxWidth, gap)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { place, index ->
                        val selected = index == chosen
                        ToggleButton(
                            checked = selected,
                            onCheckedChange = { if (!selected) onChoose(index) },
                            shapes =
                                when {
                                    row.size == 1 ->
                                        ButtonGroupDefaults.connectedMiddleButtonShapes(
                                            shape = ButtonGroupDefaults.connectedButtonCheckedShape,
                                        )
                                    place == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    place == row.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                            modifier = Modifier.weight(widths[index].value).semantics { role = Role.RadioButton },
                        ) {
                            Text(texts[index])
                        }
                    }
                }
            }
        }
    }
}

// Each label's toggle button at its own width, with a pixel to spare for rounding.
@Composable
private fun toggleButtonWidths(labels: List<String>): List<Dp> {
    val measurer = rememberTextMeasurer()
    val style = ButtonDefaults.textStyleFor(ToggleButtonDefaults.MinHeight)
    val padding = ToggleButtonDefaults.contentPaddingFor(ToggleButtonDefaults.MinHeight)
    val direction = LocalLayoutDirection.current
    val sides = padding.calculateStartPadding(direction) + padding.calculateEndPadding(direction)
    return with(LocalDensity.current) {
        labels.map {
            measurer
                .measure(it, style, softWrap = false)
                .size.width
                .plus(1)
                .toDp() + sides
        }
    }
}

@Composable
private fun Detail(
    @StringRes text: Int,
) = Text(
    stringResource(text),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = Spacing.l),
)

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
