package io.github.kauri_off.kiroku.titlesub

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.common.mediaTypeLabel
import io.github.kauri_off.kiroku.common.statusLine
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
                        // A relation's text form names it uniquely, so keys survive a group added in front.
                        val relation = group.relation.toString()
                        item(key = relation, contentType = HEADER) {
                            val gap = if (index == 0) Modifier else Modifier.padding(top = Spacing.l)
                            SectionHeader(relationName(group.relation), gap)
                        }
                        items(group.rows, key = { relation to it.id.value }, contentType = { ROW }) { row ->
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
            facts.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.common_separator)),
            row.entry?.let { statusLine(it, row.total) },
        )
    AnimeRow(title = row.title, posterUrl = row.posterUrl, onClick = onClick, lines = lines.map { RowLine(it) })
}

private const val HEADER = 0
private const val ROW = 1
