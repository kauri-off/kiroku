package io.github.kauri_off.kiroku.sample

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.design.component.TitleText

data class SampleRow(
    val id: AnimeId,
    val title: String,
    val status: ListStatus,
    val watched: Int,
    val total: Int?,
)

data class SampleState(
    val rows: List<SampleRow>,
)

sealed interface SampleEvent {
    data class Open(
        val id: AnimeId,
    ) : SampleEvent

    data class Increment(
        val id: AnimeId,
    ) : SampleEvent

    data object Add : SampleEvent

    data object Refresh : SampleEvent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleScreen(
    state: SampleState,
    onEvent: (SampleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.sample_title)) }) },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            items(state.rows, key = { it.id.value }) { row ->
                ListItem(
                    onClick = { onEvent(SampleEvent.Open(row.id)) },
                    supportingContent = { Text(stringResource(row.status.labelRes())) },
                    trailingContent = { Text(progressText(row), color = MaterialTheme.colorScheme.primary) },
                    content = { TitleText(row.title, maxLines = 2) },
                )
            }
        }
    }
}

@Composable
internal fun progressText(row: SampleRow): String =
    if (row.total == null) {
        row.watched.toString()
    } else {
        stringResource(R.string.sample_progress, row.watched, row.total)
    }

@StringRes
internal fun ListStatus.labelRes(): Int =
    when (this) {
        ListStatus.Watching -> R.string.sample_status_watching
        ListStatus.Planned -> R.string.sample_status_planned
        ListStatus.Completed -> R.string.sample_status_completed
        ListStatus.OnHold -> R.string.sample_status_on_hold
        ListStatus.Dropped -> R.string.sample_status_dropped
    }
