package io.github.kauri_off.kiroku.scenario

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Spacing

// The names of the scenarios that are on, in the panel's order.
fun Scenario.names(): List<Int> =
    buildList {
        if (offline) add(R.string.scenarios_offline)
        if (sessionExpired) add(R.string.design_session_expired)
        if (slow) add(R.string.scenarios_slow)
        when (writes) {
            WriteFault.None -> Unit
            WriteFault.Refused -> add(R.string.scenarios_name_writes_refused)
            WriteFault.ServerError -> add(R.string.scenarios_name_write_errors)
        }
        when (store) {
            StoreFault.None -> Unit
            StoreFault.WritesFail -> add(R.string.scenarios_name_store_writes_fail)
            StoreFault.Unreadable -> add(R.string.scenarios_name_store_unreadable)
        }
    }

// The marker where it floats over the app: centred on the top app bar of the screen beneath.
@Composable
fun FloatingScenarioMarker(
    scenario: Scenario,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = Box(modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = Spacing.s)) { ScenarioMarker(scenario, onClick) }

// Says that scenarios are on, so a simulated failure is not taken for a bug; it is spoken with their names, and a tap
// opens the panel. An icon fits between a top app bar's title and its actions on every screen.
@Composable
fun ScenarioMarker(
    scenario: Scenario,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!scenario.active) return
    val names = scenario.names().map { stringResource(it) }.joinToString(stringResource(R.string.common_separator))
    val colors = MaterialTheme.colorScheme
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer),
        modifier = modifier,
    ) {
        Icon(painterResource(R.drawable.ic_scenarios), contentDescription = stringResource(R.string.scenarios_marker_spoken, names))
    }
}
