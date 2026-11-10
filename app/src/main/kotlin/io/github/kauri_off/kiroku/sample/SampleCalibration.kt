package io.github.kauri_off.kiroku.sample

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.ListStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleNoiseScreen(
    state: SampleState,
    onEvent: (SampleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.sample_title)) }) },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item { NoiseSummary(state) }
            items(state.rows, key = { it.id.value }) { row ->
                Column(Modifier.clickable { onEvent(SampleEvent.Open(row.id)) }) {
                    ListItem(
                        leadingContent = { Badge { Text(row.title.take(1)) } },
                        overlineContent = { Text(stringResource(row.status.labelRes())) },
                        headlineContent = { Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent =
                            row.total?.let { total ->
                                { Text(pluralStringResource(R.plurals.sample_episodes_watched, total, row.watched, total)) }
                            },
                        trailingContent = { Text(progressText(row)) },
                    )
                    row.total?.let { total ->
                        LinearProgressIndicator(
                            progress = { row.watched.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun NoiseSummary(state: SampleState) {
    ListItem(
        headlineContent = {
            Text(pluralStringResource(R.plurals.sample_titles_on_list, state.rows.size, state.rows.size))
        },
        supportingContent = {
            Column {
                ListStatus.entries.forEach { status ->
                    Text(
                        stringResource(
                            R.string.sample_status_count,
                            stringResource(status.labelRes()),
                            state.rows.count { it.status == status },
                        ),
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleHierarchyScreen(
    state: SampleState,
    onEvent: (SampleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.sample_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onEvent(SampleEvent.Add) }) {
                Text(stringResource(R.string.sample_add))
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = { onEvent(SampleEvent.Add) }) { Text(stringResource(R.string.sample_add)) }
                    Button(onClick = { onEvent(SampleEvent.Refresh) }) { Text(stringResource(R.string.sample_refresh)) }
                }
            }
            items(state.rows, key = { it.id.value }) { row ->
                ListItem(
                    headlineContent = { Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(progressText(row)) },
                    trailingContent = {
                        Button(onClick = { onEvent(SampleEvent.Increment(row.id)) }) {
                            Text(stringResource(R.string.sample_plus_one))
                        }
                    },
                    modifier = Modifier.clickable { onEvent(SampleEvent.Open(row.id)) },
                )
            }
        }
    }
}
