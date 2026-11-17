package io.github.kauri_off.kiroku.core.model

import java.time.Instant
import java.time.LocalDate

// An entry's status as MAL gives it: one the app knows, or MAL's code of one it does not, kept as MAL gave it.
sealed interface EntryStatus {
    data class Unrecognized(
        val malCode: String,
    ) : EntryStatus
}

// The statuses the app knows, the only ones a user can choose and the app can send.
enum class ListStatus : EntryStatus { Watching, Planned, Completed, OnHold, Dropped }

data class ListEntry(
    val anime: Anime,
    val values: ListValues,
    val updatedAt: Instant,
) {
    val status: EntryStatus get() = values.status
}

data class ListValues(
    val status: EntryStatus,
    val watchedEpisodes: Int,
    val score: Int?,
    val startDate: PartialDate?,
    val finishDate: PartialDate?,
) {
    init {
        require(watchedEpisodes >= 0)
        require(score == null || score in 1..10)
    }

    fun withWatched(
        watched: Int,
        malEpisodes: Int?,
        today: LocalDate,
    ): ListValues {
        require(watched >= 0)
        val todayDate = PartialDate.Day(today)
        return when {
            malEpisodes != null && watched >= malEpisodes && status != ListStatus.Completed ->
                copy(
                    status = ListStatus.Completed,
                    watchedEpisodes = watched,
                    startDate = if (status == ListStatus.Planned) startDate ?: todayDate else startDate,
                    finishDate = finishDate ?: todayDate,
                )
            status == ListStatus.Planned && watched > 0 ->
                copy(status = ListStatus.Watching, watchedEpisodes = watched, startDate = startDate ?: todayDate)
            else -> copy(watchedEpisodes = watched)
        }
    }

    fun markWatched(
        episode: Int,
        malEpisodes: Int?,
        today: LocalDate,
    ): ListValues = if (episode <= watchedEpisodes) this else withWatched(episode, malEpisodes, today)
}

// A value of an entry, as a write sets it.
enum class ListField(
    val of: (ListValues) -> Any?,
) {
    Status(ListValues::status),
    Score(ListValues::score),
    WatchedEpisodes(ListValues::watchedEpisodes),
    StartDate(ListValues::startDate),
    FinishDate(ListValues::finishDate),
    ;

    companion object {
        // Values that only mean something together: W7 moves the status, the count and the dates as one.
        val units: List<Set<ListField>> = listOf(setOf(Status, WatchedEpisodes, StartDate, FinishDate), setOf(Score))
    }
}

fun ListValues.changedFrom(other: ListValues): Set<ListField> = ListField.entries.filterTo(mutableSetOf()) { it.of(this) != it.of(other) }

// These values' [fields], and [base]'s for the rest.
fun ListValues.over(
    base: ListValues,
    fields: Set<ListField>,
): ListValues {
    fun <T> pick(
        field: ListField,
        value: (ListValues) -> T,
    ): T = value(if (field in fields) this else base)
    return ListValues(
        pick(ListField.Status) { it.status },
        pick(ListField.WatchedEpisodes) { it.watchedEpisodes },
        pick(ListField.Score) { it.score },
        pick(ListField.StartDate) { it.startDate },
        pick(ListField.FinishDate) { it.finishDate },
    )
}

// Whether the episode count follows the status where it is shown; it says nothing for Planned with none watched or
// for Completed at the total.
fun ListValues.showsCount(total: Int?): Boolean =
    when (status) {
        ListStatus.Planned -> watchedEpisodes > 0
        ListStatus.Completed -> watchedEpisodes != total
        ListStatus.Watching, ListStatus.OnHold, ListStatus.Dropped, is EntryStatus.Unrecognized -> true
    }

// Whether a title with this status gets airing data while it is unfinished (section 4); one the app does not know does not.
val EntryStatus.tracked: Boolean
    get() =
        when (this) {
            ListStatus.Watching, ListStatus.Planned, ListStatus.OnHold -> true
            ListStatus.Completed, ListStatus.Dropped, is EntryStatus.Unrecognized -> false
        }

// Whether a title with this status may be notified of (section 5.1); one the app does not know never is.
fun EntryStatus.notified(alsoPlanned: Boolean): Boolean =
    when (this) {
        ListStatus.Watching -> true
        ListStatus.Planned -> alsoPlanned
        ListStatus.Completed, ListStatus.OnHold, ListStatus.Dropped, is EntryStatus.Unrecognized -> false
    }
