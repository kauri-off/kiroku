package io.github.kauri_off.kiroku.titlesub

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.Source
import io.github.kauri_off.kiroku.text.clockTime
import io.github.kauri_off.kiroku.text.currentLocale
import io.github.kauri_off.kiroku.text.named
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

@Composable
internal fun sourceName(source: Source): String = source.named { stringResource(it) }

@Composable
internal fun ratingName(rating: AgeRating): String = rating.named { stringResource(it) }

// "23 min", "1 h 58 min"; under a minute in seconds.
@Composable
internal fun lengthText(length: Duration): String {
    val minutes = length.toMinutes().toInt()
    val hours = minutes / MINUTES_PER_HOUR
    val rest = minutes % MINUTES_PER_HOUR
    return when {
        minutes == 0 -> stringResource(R.string.titlesub_seconds, length.seconds.toInt())
        hours == 0 -> stringResource(R.string.titlesub_minutes, minutes)
        rest == 0 -> stringResource(R.string.titlesub_hours, hours)
        else -> stringResource(R.string.titlesub_hours_minutes, hours, rest)
    }
}

// "Saturdays at 17:30" in the device's 12/24-hour setting; "Saturdays" when the time is not known.
@Composable
internal fun broadcastText(
    day: DayOfWeek,
    time: LocalTime?,
): String {
    val index = day.value - 1
    if (time == null) return stringArrayResource(R.array.titlesub_broadcast_day)[index]
    return stringArrayResource(R.array.titlesub_broadcast_at)[index].format(currentLocale(), clockTime(LocalContext.current, time))
}

private const val MINUTES_PER_HOUR = 60
