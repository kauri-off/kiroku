package io.github.kauri_off.kiroku.titlesub

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.RowLine
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.theme.Spacing

// "Related: see all" of section 9.9: every related anime, grouped under its relation's full name.
@Composable
fun RelatedScreen(
    state: RelatedState,
    onEvent: (RelatedEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val body =
        when (state) {
            RelatedState.Loading -> SubScreenBody.State(ScreenState.Loading)
            is RelatedState.Failed -> SubScreenBody.State(errorState(state.error) { onEvent(RelatedEvent.Retry) })
            is RelatedState.Shown ->
                SubScreenBody.List {
                    state.groups.forEachIndexed { index, group ->
                        item(key = index, contentType = HEADER) {
                            val gap = if (index == 0) Modifier else Modifier.padding(top = Spacing.s)
                            SectionHeader(relationName(group.relation), gap)
                        }
                        items(group.rows, key = { index to it.id.value }, contentType = { ROW }) { row ->
                            RelatedAnimeRow(row) { onEvent(RelatedEvent.Open(row.id)) }
                        }
                    }
                }
        }
    SubScreen(stringResource(R.string.titlesub_related), onBack = { onEvent(RelatedEvent.Back) }, body = body, modifier = modifier)
}

@Composable
private fun RelatedAnimeRow(
    row: RelatedRow,
    onClick: () -> Unit,
) {
    val facts = listOfNotNull(row.mediaType?.let { mediaTypeLabel(it) }, row.year?.toString())
    val lines =
        listOfNotNull(
            facts.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.titlesub_separator)),
            row.entry?.let { statusLine(it.status, it.watchedEpisodes, row.total) },
        )
    AnimeRow(title = row.title, posterUrl = row.posterUrl, onClick = onClick, lines = lines.map { RowLine(it) })
}

// "Watching · 5 / 12". The count is left out where it adds nothing: Planned with none watched, Completed at the total.
@Composable
internal fun statusLine(
    status: ListStatus,
    watched: Int,
    total: Int?,
): String {
    val name = stringResource(status.label())
    return when {
        status == ListStatus.Planned && watched == 0 -> name
        status == ListStatus.Completed && watched == total -> name
        total == null -> stringResource(R.string.titlesub_status_count, name, watched)
        else -> stringResource(R.string.titlesub_status_progress, name, watched, total)
    }
}

private const val HEADER = 0
private const val ROW = 1
