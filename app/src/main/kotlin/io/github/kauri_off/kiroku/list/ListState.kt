package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.episodeTotal
import io.github.kauri_off.kiroku.core.airing.episodesToWatch
import io.github.kauri_off.kiroku.core.airing.premiereOf
import io.github.kauri_off.kiroku.core.airing.shownAired
import io.github.kauri_off.kiroku.core.list.ListSort
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
    // The text typed into the filter field, which filters every tab; null when the list is not being filtered.
    val filter: String? = null,
    val refreshing: Boolean = false,
    val sessionExpired: Boolean = false,
    val message: ListMessage? = null,
    // The moment air times are written against: "today", "tomorrow", a weekday or a date.
    val now: Instant,
) {
    val page: ListPage? get() = (content as? ListContent.Tabs)?.pages?.get(tab)
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

// One tab: its rows, filtered when a filter is typed, in the order last computed for [sort].
data class ListPage(
    val sort: ListSort,
    val sorts: List<ListSort>,
    val rows: List<ListRow>,
    // Changes whenever the tab's order is computed again, which starts the page from the top; rows animate only
    // between states with the same value. A tab switch does not change it.
    val ordering: Int = 0,
)

enum class ListFailure { NoConnection, Refused, Other }

// A snackbar; [serial] tells two equal messages in a row apart.
data class ListMessage(
    val kind: Kind,
    val serial: Long,
) {
    sealed interface Kind {
        data class EpisodeWatched(
            val episode: Int,
        ) : Kind

        data object MovedToCompleted : Kind

        data class RefreshFailed(
            val failure: ListFailure,
        ) : Kind
    }
}

data class ListRow(
    val id: AnimeId,
    val title: String,
    val posterUrl: String?,
    val line2: Line2,
    val line3: Line3?,
    val plusOne: Boolean,
)

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

    data object StartFilter : ListEvent

    data class FilterChanged(
        val text: String,
    ) : ListEvent

    data object EndFilter : ListEvent

    data object OpenSort : ListEvent

    data class SelectSort(
        val sort: ListSort,
    ) : ListEvent

    data object DismissSort : ListEvent

    data object Refresh : ListEvent

    data class Undo(
        val message: ListMessage,
    ) : ListEvent

    data class MessageDismissed(
        val message: ListMessage,
    ) : ListEvent

    data object Retry : ListEvent

    data object SignIn : ListEvent

    data object Search : ListEvent

    data object Back : ListEvent
}

// One row from :core's values: the aired count, "to watch", the total, the next episode and the premiere are never
// computed here. Another user's list has no airing data, so its row has progress or score and no third line.
fun listRow(
    entry: ListEntry,
    airing: Airing?,
    today: LocalDate,
    language: TitleLanguage,
    readOnly: Boolean = false,
): ListRow {
    val anime = entry.anime
    val values = entry.values
    val ownAiring = airing.takeUnless { readOnly }
    val premiere = if (readOnly) null else premiereOf(anime, airing, today)
    val aired = shownAired(anime, ownAiring)
    val line2 =
        when {
            entry.status == ListStatus.Completed -> Line2.Score(values.score)
            premiere != null -> Line2.Premieres(premiere)
            else -> Line2.Progress(values.watchedEpisodes, episodeTotal(anime, airing))
        }
    val toWatch = episodesToWatch(entry, ownAiring)
    val next = ownAiring?.nextEpisode
    val line3 =
        when {
            line2 !is Line2.Progress || aired == null -> null
            toWatch != null -> Line3.ToWatch(toWatch)
            next != null -> Line3.NextEpisode(next)
            else -> null
        }
    return ListRow(
        id = anime.id,
        title = anime.titles.preferred(language),
        posterUrl = anime.posterUrl,
        line2 = line2,
        line3 = line3,
        plusOne = !readOnly && entry.status == ListStatus.Watching,
    )
}
