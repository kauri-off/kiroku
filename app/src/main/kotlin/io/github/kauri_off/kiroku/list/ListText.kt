package io.github.kauri_off.kiroku.list

import android.content.Context
import androidx.annotation.StringRes
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.SortDirection.Ascending
import io.github.kauri_off.kiroku.core.list.SortDirection.Descending
import io.github.kauri_off.kiroku.text.AirTimeText
import io.github.kauri_off.kiroku.text.premiereText
import java.time.Instant
import java.time.ZoneId

// The words of a list row, in the device's language, time zone and 12- or 24-hour setting. Air times are written by
// the same AirTimeText as the title page's.
class ListText(
    private val context: Context,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    private val times = AirTimeText(context, now, zone)

    // Line 2, followed by the value the tab is sorted by when the row has one: "Watched 12 of 24 · Score 8", "… · 2025".
    fun line2(
        line: Line2,
        sortedBy: SortedBy?,
    ): String = sortedBy?.let { context.getString(R.string.list_line2_sorted_by, line2(line), sortedBy(it)) } ?: line2(line)

    private fun sortedBy(value: SortedBy): String =
        when (value) {
            is SortedBy.Score ->
                value.score?.let { context.getString(R.string.list_sorted_by_score, it) }
                    ?: context.getString(R.string.list_sorted_by_not_scored)
            is SortedBy.StartYear -> value.year.toString()
        }

    fun line2(line: Line2): String =
        when (line) {
            is Line2.Progress ->
                if (line.total == null) {
                    context.getString(R.string.list_progress_unknown_total, line.watched)
                } else {
                    context.getString(R.string.list_progress, line.watched, line.total)
                }
            is Line2.Premieres -> premiere(line.premiere)
            is Line2.Score ->
                line.score?.let { context.getString(R.string.list_score, it) } ?: context.getString(R.string.list_not_scored)
        }

    fun line3(line: Line3): String =
        when (line) {
            is Line3.ToWatch ->
                when (line.count) {
                    is AiredCount.Exact -> context.getString(R.string.list_to_watch, line.count.value)
                    is AiredCount.Estimated -> context.getString(R.string.list_to_watch_estimated, line.count.value)
                }
            is Line3.NextEpisode -> times.nextEpisode(line.episode)
        }

    fun premiere(premiere: Premiere): String = premiereText(context, times, premiere, withYear = false)
}

// The two directions of a sort, worded for that sort, its own one first; "Up next" has none.
fun ListSort.directions(): List<Pair<SortDirection, Int>> =
    when (this) {
        ListSort.UpNext -> emptyList()
        ListSort.Title -> listOf(Ascending to R.string.list_direction_a_to_z, Descending to R.string.list_direction_z_to_a)
        ListSort.LastUpdated -> listOf(Descending to R.string.list_direction_newest, Ascending to R.string.list_direction_oldest)
        ListSort.Score -> listOf(Descending to R.string.list_direction_highest, Ascending to R.string.list_direction_lowest)
        ListSort.StartDate -> listOf(Ascending to R.string.list_direction_earliest, Descending to R.string.list_direction_latest)
    }

@StringRes
fun ListSort.labelRes(): Int =
    when (this) {
        ListSort.UpNext -> R.string.list_sort_up_next
        ListSort.Title -> R.string.list_sort_title
        ListSort.LastUpdated -> R.string.list_sort_last_updated
        ListSort.Score -> R.string.list_sort_score
        ListSort.StartDate -> R.string.list_sort_start_date
    }
