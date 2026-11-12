package io.github.kauri_off.kiroku.scenario

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.notShown

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

// The marker on the end edge of [area], a little below its middle, where every screen has content under its bars.
@Composable
fun EdgeMarker(
    scenario: Scenario,
    area: Modifier,
    onClick: () -> Unit,
) = Box(area.fillMaxSize().padding(top = Spacing.xxl)) { ScenarioMarker(scenario, onClick, Modifier.align(Alignment.CenterEnd)) }

// A tab that opens the debug menu, touched only within the side margin; red and spoken with what is on while a scenario is.
@Composable
fun ScenarioMarker(
    scenario: Scenario,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spoken =
        if (scenario.active) {
            val names = scenario.names().map { stringResource(it) }.joinToString(stringResource(R.string.common_separator))
            stringResource(R.string.scenarios_marker_spoken, names)
        } else {
            stringResource(R.string.debug_menu_title)
        }
    val configuration = LocalViewConfiguration.current
    val exact =
        remember(configuration) {
            object : ViewConfiguration by configuration {
                override val minimumTouchTargetSize = DpSize.Zero
            }
        }
    // Compose widens a small target for a finger; here the touched area is exactly the margin's width.
    CompositionLocalProvider(LocalViewConfiguration provides exact, LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Box(
            modifier
                .size(width = Spacing.l, height = TAB_HEIGHT)
                .testTag(MARKER_TAG)
                .clickable(interactionSource = null, indication = null, onClick = onClick)
                .semantics { contentDescription = spoken },
        ) {
            Surface(
                shape = RoundedCornerShape(topStartPercent = HALF, bottomStartPercent = HALF),
                color = if (scenario.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.CenterEnd).size(width = Spacing.m, height = TAB_HEIGHT),
            ) {}
        }
    }
}

private const val HALF = 50

private val TAB_HEIGHT = Sizes.touchTarget + Spacing.xl

// The tab's test tag: narrower than 48 dp, so it fits the screen's side margin.
val MARKER_TAG = notShown("scenario-marker")
