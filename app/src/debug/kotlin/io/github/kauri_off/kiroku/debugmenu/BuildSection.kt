package io.github.kauri_off.kiroku.debugmenu

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.FactRow
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.text.currentLocale
import io.github.kauri_off.kiroku.text.skeletonFormat
import java.time.Instant
import java.time.ZoneId

@Composable
fun BuildRoute() = BuildSection(remember { BuildInfo.read() })

// What is running. Selectable, so the commit can be copied into a report.
@Composable
fun BuildSection(build: BuildInfo) {
    SectionHeader(stringResource(R.string.debug_menu_build))
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
