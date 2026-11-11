package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.episodeTotal
import io.github.kauri_off.kiroku.core.airing.premiereOf
import io.github.kauri_off.kiroku.core.airing.toWatch
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.model.AiringStatus
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
    val sort: ListSort = ListSort.UpNext,
    val sorts: List<ListSort> = ListSort.entries,
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

        data object CouldNotRefresh : Kind
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
    // `12 / 24 · 14 aired`; the aired part only while the title airs and its count is known.
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
    val total = episodeTotal(anime, airing)
    val premiere = if (readOnly) null else premiereOf(anime, airing, today)
    val line2 =
        when {
            entry.status == ListStatus.Completed -> Line2.Score(values.score)
            premiere != null -> Line2.Premieres(premiere)
            else ->
                Line2.Progress(
                    watched = values.watchedEpisodes,
                    total = total,
                    aired = (airing?.aired as? AiredCount.Known)?.takeIf { anime.airingStatus == AiringStatus.Airing && !readOnly },
                )
        }
    val toWatch = toWatch(entry, airing)?.takeIf { it.value > 0 }
    val next = airing?.nextEpisode?.takeIf { airing.aired is AiredCount.Exact }
    val line3 =
        when {
            readOnly || line2 !is Line2.Progress || line2.aired == null -> null
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
        plusOne = !readOnly && entry.status == ListStatus.Watching && (total == null || values.watchedEpisodes < total),
    )
}
