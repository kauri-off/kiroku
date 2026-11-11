package io.github.kauri_off.kiroku.titlesub

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.RowLine
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.text.relationName
import io.github.kauri_off.kiroku.text.statusLine
import io.github.kauri_off.kiroku.text.typeAndYear

// "Related: see all" of section 9.9: every related anime, grouped under its relation's full name.
@Composable
fun RelatedScreen(
    state: RelatedState,
    onEvent: (RelatedEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val body =
        when (state) {
            is RelatedState.Loading -> SubScreenBody.State(ScreenState.Loading)
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
    SubScreen(
        title = stringResource(R.string.title_related),
        sessionExpired = state.sessionExpired,
        onBack = { onEvent(RelatedEvent.Back) },
        onSignIn = { onEvent(RelatedEvent.SignIn) },
        snackbars = snackbars,
        body = body,
        modifier = modifier,
    )
}

@Composable
private fun RelatedAnimeRow(
    row: RelatedRow,
    onClick: () -> Unit,
) {
    val lines =
        listOfNotNull(
            typeAndYear(row.mediaType, row.year).takeIf { it.isNotEmpty() },
            row.entry?.let { statusLine(it, row.total) },
        )
    AnimeRow(title = row.title, posterUrl = row.posterUrl, onClick = onClick, lines = lines.map { RowLine(it) })
}

private const val HEADER = 0
private const val ROW = 1
