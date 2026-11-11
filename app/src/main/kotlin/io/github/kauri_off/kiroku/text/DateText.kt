package io.github.kauri_off.kiroku.text

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.notShown
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

// A date from MAL by its precision, with the year: "Oct 11, 2025", "October 2025", "2025".
@Composable
fun dateText(date: PartialDate): String =
    when (date) {
        is PartialDate.Day -> dateText(date.date)
        is PartialDate.Month -> skeletonFormat(currentLocale(), MONTH_YEAR).format(date.yearMonth)
        is PartialDate.OnlyYear -> date.year.toString()
    }

@Composable
fun dateText(date: LocalDate): String = skeletonFormat(currentLocale(), DAY_MONTH_YEAR).format(date)

// A time of day in the device's 12/24-hour setting.
fun clockTime(
    context: Context,
    time: TemporalAccessor,
): String {
    val skeleton = if (DateFormat.is24HourFormat(context)) HOURS_24 else HOURS_12
    return skeletonFormat(context.resources.configuration.locales[0], skeleton).format(time)
}

internal fun skeletonFormat(
    locale: Locale,
    skeleton: String,
): DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

private val HOURS_24 = notShown("Hm")
private val HOURS_12 = notShown("hm")
private val DAY_MONTH_YEAR = notShown("dMMMy")
private val MONTH_YEAR = notShown("LLLLy")
