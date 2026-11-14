package io.github.kauri_off.kiroku.search

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.WAVY_INDICATOR
import io.github.kauri_off.kiroku.design.theme.Spacing

// The rows of the pages loaded, then what follows them: the next page's wait, or its failure with Retry.
// [list] names what the rows are of (a query, a browse list), so a new list asks for its next page as an old one does.
internal fun LazyListScope.pagedRows(
    list: Any,
    rows: List<ResultRow>,
    more: More,
    @StringRes failed: Int,
    onOpen: (AnimeId) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    items(rows, key = { it.id.value }, contentType = { ROW }) { row -> ResultAnimeRow(row, onClick = { onOpen(row.id) }) }
    when (more) {
        More.None -> Unit
        More.Ready, More.Loading ->
            item(key = MORE, contentType = MORE) {
                LoadingMore()
                // Asks for the next page when the end comes into view, and again with each page or list that ends there.
                if (more == More.Ready) {
                    val load by rememberUpdatedState(onLoadMore)
                    LaunchedEffect(list, rows.lastOrNull()?.id) { load() }
                }
            }
        is More.Failed ->
            item(key = MORE, contentType = MORE_FAILED) {
                ScreenState(ScreenState.Error(stringResource(more.error.message(failed)), onRetry))
            }
    }
}

@Composable
private fun LoadingMore() {
    val loading = stringResource(R.string.search_loading_more)
    LinearWavyProgressIndicator(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.xl)
            .testTag(WAVY_INDICATOR)
            .semantics { contentDescription = loading },
    )
}

@StringRes
internal fun DataError.message(
    @StringRes otherwise: Int,
): Int =
    when (this) {
        DataError.NoConnection -> R.string.common_no_connection
        DataError.Refused -> R.string.common_refused
        else -> otherwise
    }

private const val ROW = 0
private const val MORE = 1
private const val MORE_FAILED = 2
