package io.github.kauri_off.kiroku.reports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.debugmenu.SectionDetail
import io.github.kauri_off.kiroku.debugmenu.dateTime
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.notShown

@Composable
fun ReportsRoute() {
    val reports by reportLog.reports.collectAsScreenState()
    // A build that is not debuggable is shrunk, as the release is, so its names are R8's.
    ReportsSection(reports, renamed = !BuildConfig.DEBUG)
}

// What the app reported, newest first, as its log says it. Selectable, so a report can be copied.
@Composable
fun ReportsSection(
    reports: List<Report>,
    renamed: Boolean,
) {
    SectionHeader(stringResource(R.string.reports_title))
    SectionDetail(if (reports.isEmpty()) R.string.reports_none else R.string.reports_detail)
    if (renamed) SectionDetail(R.string.reports_renamed)
    SelectionContainer {
        Column {
            reports.forEach { report ->
                ListItem(
                    overlineContent = { Text(dateTime(report.at)) },
                    content = { Text(shown(report.lines.first())) },
                    supportingContent = { Text(report.lines.drop(1).joinToString(LINE, transform = ::shown)) },
                )
            }
        }
    }
}

// A label stays whole, on the line of the long name it is followed by.
private fun shown(line: String) = line.trimStart().replace(LABEL) { it.value.replace(' ', '\u00A0') }

private val LABEL = Regex(notShown("^(at|[^:]+:) "))

private val LINE = notShown("\n")
