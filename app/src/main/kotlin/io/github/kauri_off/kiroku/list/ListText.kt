package io.github.kauri_off.kiroku.list

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.notShown
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// The words of a list row, in the device's language, time zone and 12- or 24-hour setting.
class ListText(
    private val context: Context,
    private val now: Instant,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val locale: Locale = context.resources.configuration.locales[0]

    fun line2(line: Line2): String =
        when (line) {
            is Line2.Progress -> {
                val progress =
                    if (line.total == null) {
                        context.getString(R.string.list_progress_unknown_total, line.watched)
                    } else {
                        context.getString(R.string.list_progress, line.watched, line.total)
                    }
                when (val aired = line.aired) {
                    null -> progress
                    is AiredCount.Exact -> context.getString(R.string.list_progress_aired, progress, aired.value)
                    is AiredCount.Estimated -> context.getString(R.string.list_progress_aired_estimated, progress, aired.value)
                }
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
            is Line3.NextEpisode -> context.getString(R.string.list_next_episode, line.episode.number, airTime(line.episode.airsAt))
        }

    fun premiere(premiere: Premiere): String =
        when (premiere) {
            is Premiere.At -> context.getString(R.string.list_premieres_at, airTime(premiere.instant))
            is Premiere.OnDay -> context.getString(R.string.list_premieres_day, pattern(DAY_MONTH).format(premiere.date))
            is Premiere.InMonth ->
                context.getString(
                    R.string.list_premieres_month,
                    context.resources.getStringArray(R.array.list_premiere_months)[premiere.yearMonth.monthValue - 1],
                    premiere.yearMonth.year,
                )
            is Premiere.InYear -> context.getString(R.string.list_premieres_year, premiere.year.value)
            Premiere.Unannounced -> context.getString(R.string.list_not_yet_aired)
        }

    // "today 17:30", "tomorrow 17:30", "Sat 17:30" within six days, "14 Nov, 17:30" beyond; never a weekday and a date.
    fun airTime(instant: Instant): String {
        val at = instant.atZone(zone)
        val time = pattern(if (DateFormat.is24HourFormat(context)) HOURS_24 else HOURS_12).format(at)
        return when (ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), at.toLocalDate())) {
            0L -> context.getString(R.string.list_time_today, time)
            1L -> context.getString(R.string.list_time_tomorrow, time)
            in 2L..6L -> context.getString(R.string.list_time_weekday, pattern(WEEKDAY).format(at), time)
            else -> context.getString(R.string.list_time_date, pattern(DAY_MONTH).format(at), time)
        }
    }

    private fun pattern(skeleton: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    private companion object {
        val HOURS_24 = notShown("Hm")
        val HOURS_12 = notShown("hm")
        val WEEKDAY = notShown("EEE")
        val DAY_MONTH = notShown("dMMM")
    }
}

@StringRes
fun ListStatus.labelRes(): Int =
    when (this) {
        ListStatus.Watching -> R.string.list_status_watching
        ListStatus.Planned -> R.string.list_status_planned
        ListStatus.Completed -> R.string.list_status_completed
        ListStatus.OnHold -> R.string.list_status_on_hold
        ListStatus.Dropped -> R.string.list_status_dropped
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
