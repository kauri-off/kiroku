package io.github.kauri_off.kiroku.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.design.theme.Spacing

data class ShellState(
    val selected: Destination,
)

sealed interface ShellEvent {
    data class Select(
        val destination: Destination,
    ) : ShellEvent
}

// The bar on phones and the rail on wide windows around the current destination's content.
@Composable
fun ShellScreen(
    state: ShellState,
    onEvent: (ShellEvent) -> Unit,
    content: @Composable () -> Unit,
) {
    val type = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfoV2())
    val rail = type == NavigationSuiteType.NavigationRail
    Surface(color = MaterialTheme.colorScheme.background) {
        NavigationSuiteScaffoldLayout(
            navigationSuite = { if (rail) Rail(state, onEvent) else Bar(state, onEvent) },
            layoutType = if (rail) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar,
        ) {
            val consumed =
                if (rail) {
                    NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
                } else {
                    NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
                }
            Box(Modifier.consumeWindowInsets(consumed)) { content() }
        }
    }
}

@Composable
private fun Bar(
    state: ShellState,
    onEvent: (ShellEvent) -> Unit,
) = NavigationBar {
    Destination.entries.forEach { destination ->
        val selected = destination == state.selected
        NavigationBarItem(
            selected = selected,
            onClick = { onEvent(ShellEvent.Select(destination)) },
            icon = { DestinationIcon(destination, selected) },
            label = { Text(stringResource(destination.label)) },
        )
    }
}

// The extra top inset puts the first item's indicator on the centre line of the top app bar beside it.
@Composable
private fun Rail(
    state: ShellState,
    onEvent: (ShellEvent) -> Unit,
) = NavigationRail(windowInsets = NavigationRailDefaults.windowInsets.add(WindowInsets(top = Spacing.m))) {
    Destination.entries.forEach { destination ->
        val selected = destination == state.selected
        NavigationRailItem(
            selected = selected,
            onClick = { onEvent(ShellEvent.Select(destination)) },
            icon = { DestinationIcon(destination, selected) },
            label = { Text(stringResource(destination.label)) },
        )
    }
}

@Composable
private fun DestinationIcon(
    destination: Destination,
    selected: Boolean,
) = Icon(painterResource(if (selected) destination.selectedIcon else destination.icon), contentDescription = null)
