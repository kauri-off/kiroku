package io.github.kauri_off.kiroku.airingcounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.debugmenu.SectionDetail
import io.github.kauri_off.kiroku.debugmenu.dateTime
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.wiring.appGraph

@Composable
fun AiringCountsRoute() {
    val graph = LocalContext.current.appGraph
    var reads by remember { mutableIntStateOf(0) }
    val state by remember(graph, reads) {
        airingCountsState(graph.list, graph.airing, graph.titleLanguage)
    }.collectAsScreenState(AiringCountsState.Reading)
    AiringCountsSection(state) { event ->
        when (event) {
            AiringCountsEvent.Retry -> reads++
        }
    }
}

// How each tracked title's aired count is known, as stored, and each title whose count is not exact with why.
@Composable
fun AiringCountsSection(
    state: AiringCountsState,
    onEvent: (AiringCountsEvent) -> Unit,
) {
    SectionHeader(stringResource(R.string.airing_counts_title))
    SectionDetail(R.string.airing_counts_detail)
    when (state) {
        AiringCountsState.Reading -> Unit
        AiringCountsState.Unreadable -> {
            Text(
                stringResource(R.string.airing_counts_unreadable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = Spacing.l, top = Spacing.s, end = Spacing.l),
            )
            OutlinedButton(
                onClick = { onEvent(AiringCountsEvent.Retry) },
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            ) {
                Text(stringResource(R.string.airing_counts_retry))
            }
        }
        is AiringCountsState.Read -> Counts(state.counts)
    }
}

@Composable
private fun Counts(counts: AiringCounts) {
    SelectionContainer {
        Column {
            CountRow(stringResource(R.string.airing_counts_tracked), counts.tracked)
            for (kind in CountKind.entries) {
                val titles = counts.notExact.filter { it.kind == kind }
                CountRow(stringResource(kind.label), counts.counts.getValue(kind), titles)
            }
        }
    }
}

@Composable
private fun CountRow(
    label: String,
    count: Int,
    titles: List<CountedTitle> = emptyList(),
) {
    ListItem(
        content = { Text(label) },
        trailingContent = { Text(count.toString(), style = MaterialTheme.typography.titleMedium) },
        supportingContent =
            if (titles.isEmpty()) {
                null
            } else {
                {
                    Column(Modifier.padding(top = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        titles.forEach { TitleLines(it) }
                    }
                }
            },
    )
}

@Composable
private fun TitleLines(title: CountedTitle) {
    Column {
        Text(title.title, color = MaterialTheme.colorScheme.onSurface)
        val count = if (title.why == Why.NoResult) null else aired(title.aired)
        Text(listOfNotNull(count, title.why?.let { why(it) }).joinToString(stringResource(R.string.airing_counts_separator)))
    }
}

@Composable
private fun aired(aired: AiredCount): String =
    when (aired) {
        is AiredCount.Exact -> stringResource(R.string.airing_counts_aired, aired.value)
        is AiredCount.Estimated -> stringResource(R.string.airing_counts_aired_estimated, aired.value)
        AiredCount.Unknown -> stringResource(R.string.airing_counts_aired_unknown)
    }

@Composable
private fun why(why: Why): String =
    when (why) {
        is Why.NumberedFrom -> stringResource(R.string.airing_counts_numbered, why.first, why.last)
        is Why.TotalsDiffer -> stringResource(R.string.airing_counts_totals, why.mal, why.schedule)
        is Why.StoppedAfter -> stringResource(R.string.airing_counts_stopped, dateTime(why.lastAired))
        Why.NoResult -> stringResource(R.string.airing_counts_no_result)
    }

private val CountKind.label: Int
    get() =
        when (this) {
            CountKind.Exact -> R.string.airing_counts_exact
            CountKind.NumberedDifferently -> R.string.airing_counts_numbered_differently
            CountKind.Doubt -> R.string.airing_counts_doubt
            CountKind.NoSchedule -> R.string.airing_counts_no_schedule
            CountKind.Unknown -> R.string.airing_counts_unknown
        }
