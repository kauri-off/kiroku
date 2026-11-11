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
    val sort: ListSort,
    val sorts: List<ListSort>,
    // Changes whenever the tab's order is computed again; rows animate only between states with the same value.
    val ordering: Int = 0,
    val sortSheetOpen: Boolean = false,
    // The text typed into the filter field; null when the list is not being filtered.
    val filter: String? = null,
    val refreshing: Boolean = false,
    val sessionExpired: Boolean = false,
    val message: ListMessage? = null,
    // The moment air times are written against: "today", "tomorrow", a weekday or a date.
    val now: Instant,
)

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

    // The rows of the current tab, filtered when a filter is typed, in the order last computed.
    data class Rows(
        val rows: List<ListRow>,
    ) : ListContent
}

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
    // `12 / 24 · 14 aired`; the aired part is :core's shownAired.
    data class Progress(
        val watched: Int,
        val total: Int?,
        val aired: AiredCount.Known?,
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
            else -> Line2.Progress(values.watchedEpisodes, episodeTotal(anime, airing), aired)
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
