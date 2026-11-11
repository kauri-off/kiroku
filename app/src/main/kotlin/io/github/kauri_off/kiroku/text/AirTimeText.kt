package io.github.kauri_off.kiroku.text

import android.content.Context
import android.text.format.DateFormat
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.notShown
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Air times by ARCHITECTURE section 10, one form for every screen, in the given zone and the device's 12/24-hour setting.
class AirTimeText(
    private val context: Context,
    private val now: Instant,
    private val zone: ZoneId,
) {
    // "today 17:30", "tomorrow 17:30", "Sat 17:30" within six days, "14 Nov, 17:30" beyond; never a weekday and a date.
    fun airTime(instant: Instant): String {
        val at = instant.atZone(zone)
        val time = pattern(if (DateFormat.is24HourFormat(context)) HOURS_24 else HOURS_12).format(at)
        return when (ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), at.toLocalDate())) {
            0L -> context.getString(R.string.air_time_today, time)
            1L -> context.getString(R.string.air_time_tomorrow, time)
            in 2L..LAST_WEEKDAY -> context.getString(R.string.air_time_weekday, pattern(WEEKDAY).format(at), time)
            else -> context.getString(R.string.air_time_date, pattern(DAY_MONTH).format(at), time)
        }
    }

    // "Premieres Sat 17:30", a premiere whose first episode's time is known.
    fun premiere(instant: Instant): String = context.getString(R.string.air_time_premieres, airTime(instant))

    // "Episode 15 · Sat 17:30"; a wrap falls before the dot.
    fun nextEpisode(episode: ScheduledEpisode): String =
        context.getString(R.string.air_time_next_episode, episode.number, airTime(episode.airsAt))

    private fun pattern(skeleton: String): DateTimeFormatter {
        val locale = context.resources.configuration.locales[0]
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }

    private companion object {
        const val LAST_WEEKDAY = 6L
        val HOURS_24 = notShown("Hm")
        val HOURS_12 = notShown("hm")
        val WEEKDAY = notShown("EEE")
        val DAY_MONTH = notShown("dMMM")
    }
}
