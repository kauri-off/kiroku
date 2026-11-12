package io.github.kauri_off.kiroku.debugmenu

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.FactRow
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.scenario.ScenarioEvent
import io.github.kauri_off.kiroku.scenario.ScenarioSection
import io.github.kauri_off.kiroku.scenario.ScenarioState
import io.github.kauri_off.kiroku.text.currentLocale
import io.github.kauri_off.kiroku.text.skeletonFormat
import java.time.Instant
import java.time.ZoneId

data class DebugMenuState(
    val scenarios: ScenarioState,
    val build: BuildInfo,
)

sealed interface DebugMenuEvent {
    data class Scenario(
        val event: ScenarioEvent,
    ) : DebugMenuEvent

    data object OpenGallery : DebugMenuEvent

    data object Close : DebugMenuEvent
}

// The tools of debug and preview builds: the demo's scenarios first, then what is running and the component gallery.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugMenuScreen(
    state: DebugMenuState,
    onEvent: (DebugMenuEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_menu_title)) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(DebugMenuEvent.Close) }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = Sizes.contentMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Spacing.xl),
            ) {
                ScenarioSection(state.scenarios, onEvent = { onEvent(DebugMenuEvent.Scenario(it)) })
                BuildSection(state.build)
                SectionHeader(stringResource(R.string.debug_menu_design), Modifier.padding(top = Spacing.xl))
                ListItem(
                    onClick = { onEvent(DebugMenuEvent.OpenGallery) },
                    supportingContent = { Text(stringResource(R.string.debug_menu_gallery_detail)) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                    content = { Text(stringResource(R.string.gallery_title)) },
                )
            }
        }
    }
}

// Selectable, so the commit can be copied into a report.
@Composable
private fun BuildSection(build: BuildInfo) {
    SectionHeader(stringResource(R.string.debug_menu_build), Modifier.padding(top = Spacing.xl))
    val notKnown = stringResource(R.string.debug_menu_not_known)
    val commit =
        build.commit?.let { if (it.dirty) stringResource(R.string.debug_menu_commit_dirty, it.hash) else it.hash } ?: notKnown
    SelectionContainer {
        Column {
            FactRow(stringResource(R.string.debug_menu_build_type), build.type)
            FactRow(stringResource(R.string.debug_menu_version), build.version)
            FactRow(stringResource(R.string.debug_menu_commit), commit)
            FactRow(stringResource(R.string.debug_menu_built), build.built?.let { dateTime(it) } ?: notKnown)
        }
    }
}

// The day and the time of day, in the device's zone and 12/24-hour setting.
@Composable
private fun dateTime(instant: Instant): String {
    val skeleton = if (DateFormat.is24HourFormat(LocalContext.current)) DAY_TIME_24 else DAY_TIME_12
    return skeletonFormat(currentLocale(), skeleton).format(instant.atZone(ZoneId.systemDefault()))
}

private val DAY_TIME_24 = notShown("dMMMyHm")
private val DAY_TIME_12 = notShown("dMMMyhm")
