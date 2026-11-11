package io.github.kauri_off.kiroku.text

import android.content.Context
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
        val time = clockTime(context, at)
        return when (ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), at.toLocalDate())) {
            0L -> context.getString(R.string.air_time_today, time)
            1L -> context.getString(R.string.air_time_tomorrow, time)
            in 2L..LAST_WEEKDAY -> context.getString(R.string.air_time_weekday, pattern(WEEKDAY).format(at), time)
            else -> context.getString(R.string.air_time_date, pattern(DAY_MONTH).format(at), time)
        }
    }

    // "Episode 15 · Sat 17:30".
    fun nextEpisode(episode: ScheduledEpisode): String =
        context.getString(R.string.air_time_next_episode, episode.number, airTime(episode.airsAt))

    private fun pattern(skeleton: String): DateTimeFormatter = skeletonFormat(context.resources.configuration.locales[0], skeleton)

    private companion object {
        const val LAST_WEEKDAY = 6L
        val WEEKDAY = notShown("EEE")
        val DAY_MONTH = notShown("dMMM")
    }
}
