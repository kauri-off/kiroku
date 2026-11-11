package io.github.kauri_off.kiroku.text

import android.content.Context
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.airing.Premiere

// "Premieres 4 Jan" in a list row, "Premieres 4 Jan 2027" on the title page; a known time as [times] writes it.
fun premiereText(
    context: Context,
    times: AirTimeText,
    premiere: Premiere,
    withYear: Boolean,
): String {
    val locale = context.resources.configuration.locales[0]
    return when (premiere) {
        is Premiere.At -> times.premiere(premiere.instant)
        is Premiere.OnDay ->
            context.getString(
                R.string.common_premieres_on,
                skeletonFormat(locale, if (withYear) DAY_MONTH_YEAR else DAY_MONTH).format(premiere.date),
            )
        is Premiere.InMonth ->
            context.resources
                .getStringArray(R.array.common_premieres_in_month)[premiere.yearMonth.monthValue - 1]
                .format(locale, premiere.yearMonth.year)
        is Premiere.InYear -> context.getString(R.string.common_premieres_in_year, premiere.year.value)
        Premiere.Unannounced -> context.getString(R.string.common_not_yet_aired)
    }
}
