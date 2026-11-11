package io.github.kauri_off.kiroku.list

import android.content.Context
import androidx.annotation.StringRes
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.list.ListSort
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

@StringRes
fun ListSort.labelRes(): Int =
    when (this) {
        ListSort.UpNext -> R.string.list_sort_up_next
        ListSort.Title -> R.string.list_sort_title
        ListSort.LastUpdated -> R.string.list_sort_last_updated
        ListSort.Score -> R.string.list_sort_score
        ListSort.StartDate -> R.string.list_sort_start_date
    }
