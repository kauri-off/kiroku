package io.github.kauri_off.kiroku.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.showsCount
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.RowLine
import io.github.kauri_off.kiroku.design.component.RowTrailing
import io.github.kauri_off.kiroku.text.statusLine
import io.github.kauri_off.kiroku.title.HeaderFacts
import io.github.kauri_off.kiroku.title.factsLine
import io.github.kauri_off.kiroku.title.scoreText

// A title as search results, browse lists and recommendations show it (ARCHITECTURE 9.6, 9.9).
data class ResultRow(
    val id: AnimeId,
    val title: String,
    val posterUrl: String?,
    val facts: HeaderFacts,
    // The user's values, when the title is on their list, and the total its status line counts against.
    val entry: ListValues?,
    val total: Int?,
    val score: Double?,
    // The place in a ranked browse list.
    val rank: Int? = null,
)

// The facts line leaves out the episode count when the status line states it, and when it is 1.
fun resultRow(
    anime: Anime,
    entry: ListValues?,
    language: TitleLanguage,
    rank: Int? = null,
): ResultRow {
    val total = anime.malEpisodes
    val countStated = entry != null && total != null && entry.showsCount(total)
    return ResultRow(
        id = anime.id,
        title = anime.titles.preferred(language),
        posterUrl = anime.posterUrl,
        facts = HeaderFacts(anime.mediaType, anime.startDate?.year, total?.takeIf { it > 1 && !countStated }),
        entry = entry,
        total = total,
        score = anime.meanScore,
        rank = rank,
    )
}

// The title, with its place in front in a ranked list; the facts line; the list status line when the title is on the
// list; the MAL score.
@Composable
fun ResultAnimeRow(
    row: ResultRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = row.rank?.let { stringResource(R.string.search_ranked_title, it, row.title) } ?: row.title
    val lines =
        listOfNotNull(
            factsLine(row.facts).takeIf { it.isNotEmpty() },
            row.entry?.let { statusLine(it, row.total) },
        )
    AnimeRow(
        title = title,
        posterUrl = row.posterUrl,
        onClick = onClick,
        modifier = modifier,
        lines = lines.map { RowLine(it) },
        trailing = row.score?.let { RowTrailing.MalScore(scoreText(it)) },
    )
}
