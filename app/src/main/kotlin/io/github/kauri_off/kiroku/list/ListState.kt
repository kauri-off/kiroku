package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.titleAiring
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.defaultDirection
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.user.UserListProgress
import io.github.kauri_off.kiroku.notShown
import java.time.Instant

data class ListState(
    val owner: ListOwner,
    val content: ListContent,
    val tab: EntryStatus = ListStatus.Watching,
    val sortSheetOpen: Boolean = false,
    val filter: String? = null,
    val refreshing: Boolean = false,
    val sessionExpired: Boolean = false,
    // The moment air times are written against: "today", "tomorrow", a weekday or a date.
    val now: Instant,
    // The filter field's own text, which the rows follow a moment later; when absent the field shows [filter].
    val typed: Typed? = null,
    // How far the read of another user's list is, the first one or one again; null while none is under way.
    val reading: UserListProgress? = null,
    // The read is one again, under a list read before that stays shown.
    val readingAgain: Boolean = false,
    // How many titles another user's whole list has, when one is held.
    val listSize: Int? = null,
    val matches: List<ListRow>? = null,
    val filterSession: Int = 0,
    val mangaAvailable: Boolean = false,
) {
    init {
        require(reading == null || owner is ListOwner.Other)
        require(listSize == null || owner is ListOwner.Other)
        require(!readingAgain || reading != null)
    }

    val page: ListPage? get() = (content as? ListContent.Tabs)?.pages?.get(tab)

    // What the filter field shows; null while it is closed.
    val filterText: String? get() = if (typed != null) typed.text else filter

    val activeFilterSession: Int get() = typed?.session ?: filterSession

    data class Typed(
        val text: String?,
        val session: Int = 0,
    ) {
        fun withText(next: String?): Typed = Typed(next, if (text == null && next != null) session + 1 else session)
    }
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
        val pages: Map<EntryStatus, ListPage>,
    ) : ListContent {
        init {
            require(pages.keys.containsAll(ListStatus.entries))
        }

        val statuses: List<EntryStatus> = tabOrder(pages.keys)
    }
}

// The five tabs, then one for each status the app does not know, by MAL's code.
internal fun tabOrder(statuses: Collection<EntryStatus>): List<EntryStatus> =
    ListStatus.entries + statuses.filterIsInstance<EntryStatus.Unrecognized>().distinct().sortedBy { it.malCode }

// A tab as a saved state holds it; a status the app does not know by MAL's code.
internal fun savedTab(status: EntryStatus): String =
    when (status) {
        is ListStatus -> status.name
        is EntryStatus.Unrecognized -> UNRECOGNIZED_TAB + status.malCode
    }

internal fun tabOf(saved: String): EntryStatus =
    if (saved.startsWith(UNRECOGNIZED_TAB)) EntryStatus.Unrecognized(saved.removePrefix(UNRECOGNIZED_TAB)) else ListStatus.valueOf(saved)

private val UNRECOGNIZED_TAB = notShown("?")

data class ListPage(
    val sort: ListSort,
    val sorts: List<ListSort>,
    val rows: List<ListRow>,
    // Changes whenever the tab's order is computed again, which starts its page from the top; rows animate only within one.
    val ordering: Int = 0,
    val direction: SortDirection? = sort.defaultDirection,
)

// The last two are another user's list's: their own answer about it.
enum class ListFailure { NoConnection, Refused, NoClientId, Other, PrivateList, UnknownUser }

data class ListRow(
    val id: AnimeId,
    val title: String,
    val posterUrl: String?,
    val line2: Line2,
    val line3: Line3?,
    val plusOne: Boolean,
    val status: EntryStatus,
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
        val status: EntryStatus,
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

    data object OpenManga : ListEvent

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

// One row from :core's result for the title, as on the title page; sorted by score or start date, line 2 also shows that value.
fun listRow(
    entry: ListEntry,
    airing: Airing?,
    now: Instant,
    language: TitleLanguage,
    readOnly: Boolean = false,
    sort: ListSort? = null,
): ListRow {
    val anime = entry.anime
    val values = entry.values
    val shown = titleAiring(anime, values, airing, now)
    val phase = shown.phase.takeUnless { readOnly }
    val line2 =
        when {
            entry.status == ListStatus.Completed -> Line2.Score(values.score)
            phase is AiringPhase.NotYetAired -> Line2.Premieres(phase.premiere)
            else -> Line2.Progress(values.watchedEpisodes, shown.total)
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
        status = entry.status,
        sortedBy = sortedBy,
    )
}
