package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.airingPhase
import io.github.kauri_off.kiroku.core.airing.episodeTotal
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.defaultDirection
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import java.time.Instant
import java.time.LocalDate

data class ListState(
    val owner: ListOwner,
    val content: ListContent,
    val tab: ListStatus = ListStatus.Watching,
    val sortSheetOpen: Boolean = false,
    // The text that filters every tab's rows; null when the list is not being filtered.
    val filter: String? = null,
    val refreshing: Boolean = false,
    val sessionExpired: Boolean = false,
    // The moment air times are written against: "today", "tomorrow", a weekday or a date.
    val now: Instant,
    // The filter field's own text, which the rows follow a moment later; when absent the field shows [filter].
    val typed: Typed? = null,
) {
    val page: ListPage? get() = (content as? ListContent.Tabs)?.pages?.get(tab)

    // What the filter field shows; null while it is closed.
    val filterText: String? get() = if (typed != null) typed.text else filter

    @JvmInline
    value class Typed(
        val text: String?,
    )
}

sealed interface ListOwner {
    data object Me : ListOwner

    data class Other(
        val name: String,
    ) : ListOwner
}

sealed interface ListContent {
    data object Loading : ListContent

    data object SignedOut : ListContent

    data class Failed(
        val failure: ListFailure,
    ) : ListContent

    // Every tab's page, so that a swipe shows the next tab's rows as they come in.
    data class Tabs(
        val pages: Map<ListStatus, ListPage>,
    ) : ListContent {
        init {
            require(pages.keys == ListStatus.entries.toSet())
        }
    }
}

// One tab: its rows, filtered when a filter is typed, in the order last computed for [sort] and [direction].
data class ListPage(
    val sort: ListSort,
    val sorts: List<ListSort>,
    val rows: List<ListRow>,
    // Changes whenever the tab's order is computed again, which starts its page from the top; rows animate only within one.
    val ordering: Int = 0,
    val direction: SortDirection? = sort.defaultDirection,
)

enum class ListFailure { NoConnection, Refused, Other }

data class ListRow(
    val id: AnimeId,
    val title: String,
    val posterUrl: String?,
    val line2: Line2,
    val line3: Line3?,
    val plusOne: Boolean,
    val sortedBy: SortedBy? = null,
)

// What line 2 adds after its own words so that the tab's order can be read off the rows.
sealed interface SortedBy {
    data class Score(
        val score: Int,
    ) : SortedBy

    data class StartYear(
        val year: Int,
    ) : SortedBy
}

sealed interface Line2 {
    // `Watched 12 of 24`, or `Watched 212` with no known total; never capped by the total.
    data class Progress(
        val watched: Int,
        val total: Int?,
    ) : Line2

    data class Premieres(
        val premiere: Premiere,
    ) : Line2

    data class Score(
        val score: Int?,
    ) : Line2
}

sealed interface Line3 {
    data class ToWatch(
        val count: AiredCount.Known,
    ) : Line3

    data class NextEpisode(
        val episode: ScheduledEpisode,
    ) : Line3
}

sealed interface ListEvent {
    data class SelectTab(
        val status: ListStatus,
    ) : ListEvent

    data class Open(
        val id: AnimeId,
    ) : ListEvent

    data class PlusOne(
        val id: AnimeId,
    ) : ListEvent

    // A long press on a row, or its "Edit" accessibility action: open the entry's edit sheet.
    data class Edit(
        val id: AnimeId,
    ) : ListEvent

    data object StartFilter : ListEvent

    data class FilterChanged(
        val text: String,
    ) : ListEvent

    data object EndFilter : ListEvent

    data object OpenSort : ListEvent

    data class SelectSort(
        val sort: ListSort,
    ) : ListEvent

    data class SelectDirection(
        val direction: SortDirection,
    ) : ListEvent

    data object DismissSort : ListEvent

    data object Refresh : ListEvent

    data object Retry : ListEvent

    data object SignIn : ListEvent

    data object Search : ListEvent

    data object Back : ListEvent
}

// One row from :core's airing phase, as on the title page; sorted by score or start date, line 2 also shows that value.
fun listRow(
    entry: ListEntry,
    airing: Airing?,
    today: LocalDate,
    language: TitleLanguage,
    readOnly: Boolean = false,
    sort: ListSort? = null,
): ListRow {
    val anime = entry.anime
    val values = entry.values
    val phase = if (readOnly) null else airingPhase(anime, values, airing, today)
    val line2 =
        when {
            entry.status == ListStatus.Completed -> Line2.Score(values.score)
            phase is AiringPhase.NotYetAired -> Line2.Premieres(phase.premiere)
            else -> Line2.Progress(values.watchedEpisodes, episodeTotal(anime, airing))
        }
    val aired = (phase as? AiringPhase.Aired)?.takeIf { line2 is Line2.Progress }
    val line3 = aired?.toWatch?.let(Line3::ToWatch) ?: aired?.next?.let(Line3::NextEpisode)
    val sortedBy =
        when {
            sort == ListSort.Score && line2 !is Line2.Score -> values.score?.let(SortedBy::Score)
            sort == ListSort.StartDate && line2 !is Line2.Premieres -> anime.startDate?.year?.let(SortedBy::StartYear)
            else -> null
        }
    return ListRow(
        id = anime.id,
        title = anime.titles.preferred(language),
        posterUrl = anime.posterUrl,
        line2 = line2,
        line3 = line3,
        plusOne = !readOnly && entry.status == ListStatus.Watching,
        sortedBy = sortedBy,
    )
}
