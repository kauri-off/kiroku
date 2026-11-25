package io.github.kauri_off.kiroku.search

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.RowLine
import io.github.kauri_off.kiroku.design.component.RowTrailing
import io.github.kauri_off.kiroku.manga.label
import io.github.kauri_off.kiroku.title.scoreText

data class MangaResultRow(
    val manga: MangaDetails,
    val title: String,
    val rank: Int? = null,
)

internal fun LazyListScope.pagedManga(
    list: Any,
    rows: Paged.Shown<MangaResultRow>,
    @StringRes failed: Int,
    onOpen: (MangaId) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    autoLoadMore: Boolean,
    colors: ListItemColors? = null,
) {
    pagedItems(list, rows.items, { it.manga.id.value }, rows.more, failed, onLoadMore, onRetry, autoLoadMore) { row ->
        AnimeRow(
            title = row.rank?.let { stringResource(R.string.search_ranked_title, it, row.title) } ?: row.title,
            posterUrl = row.manga.posterUrl,
            onClick = { onOpen(row.manga.id) },
            lines = listOfNotNull(row.manga.format?.let { RowLine(it.label()) }),
            trailing = row.manga.meanScore?.let { RowTrailing.MalScore(scoreText(it)) },
            colors = colors ?: ListItemDefaults.colors(),
        )
    }
}
