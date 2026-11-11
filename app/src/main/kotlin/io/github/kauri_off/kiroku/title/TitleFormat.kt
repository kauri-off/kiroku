package io.github.kauri_off.kiroku.title

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.malCodeAsText
import io.github.kauri_off.kiroku.notShown
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
@ReadOnlyComposable
private fun locale(): Locale = LocalConfiguration.current.locales[0]

// "TV · 2024 · 24 episodes": type, year and an episode count other than 1.
@Composable
fun factsLine(
    anime: Anime,
    total: Int?,
): String {
    val facts =
        listOfNotNull(
            anime.mediaType?.let { mediaTypeLabel(it) },
            anime.startDate?.year?.toString(),
            total?.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.title_episodes, it, it) },
        )
    return facts.joinToString(stringResource(R.string.title_separator))
}

@Composable
fun mediaTypeLabel(type: MediaType): String =
    when (type) {
        MediaType.Tv -> stringResource(R.string.title_type_tv)
        MediaType.TvSpecial -> stringResource(R.string.title_type_tv_special)
        MediaType.Movie -> stringResource(R.string.title_type_movie)
        MediaType.Ova -> stringResource(R.string.title_type_ova)
        MediaType.Ona -> stringResource(R.string.title_type_ona)
        MediaType.Special -> stringResource(R.string.title_type_special)
        MediaType.Music -> stringResource(R.string.title_type_music)
        MediaType.Commercial -> stringResource(R.string.title_type_commercial)
        MediaType.PromotionalVideo -> stringResource(R.string.title_type_promotional)
        is MediaType.Unrecognized -> malCodeAsText(type.malCode)
    }

@StringRes
fun ListStatus.label(): Int =
    when (this) {
        ListStatus.Watching -> R.string.title_status_watching
        ListStatus.Planned -> R.string.title_status_planned
        ListStatus.Completed -> R.string.title_status_completed
        ListStatus.OnHold -> R.string.title_status_on_hold
        ListStatus.Dropped -> R.string.title_status_dropped
    }

// The relation as caption 1 of a strip card, with the short labels of the two longest names.
@StringRes
fun RelationType.stripLabel(): Int? =
    when (this) {
        RelationType.Prequel -> R.string.title_relation_prequel
        RelationType.Sequel -> R.string.title_relation_sequel
        RelationType.ParentStory -> R.string.title_relation_parent_story
        RelationType.FullStory -> R.string.title_relation_full_story
        RelationType.SideStory -> R.string.title_relation_side_story
        RelationType.SpinOff -> R.string.title_relation_spin_off
        RelationType.AlternativeSetting -> R.string.title_relation_alt_setting
        RelationType.AlternativeVersion -> R.string.title_relation_alt_version
        RelationType.Summary -> R.string.title_relation_summary
        RelationType.Other, RelationType.Character, is RelationType.Unrecognized -> null
    }

@Composable
fun genreName(genre: Genre): String = genreNames[genre.id]?.let { stringResource(it) } ?: genre.malName

private val genreNames: Map<Int, Int> =
    mapOf(
        1 to R.string.title_genre_action,
        2 to R.string.title_genre_adventure,
        4 to R.string.title_genre_comedy,
        5 to R.string.title_genre_avant_garde,
        6 to R.string.title_genre_mythology,
        7 to R.string.title_genre_mystery,
        8 to R.string.title_genre_drama,
        10 to R.string.title_genre_fantasy,
        13 to R.string.title_genre_historical,
        14 to R.string.title_genre_horror,
        18 to R.string.title_genre_mecha,
        19 to R.string.title_genre_music,
        22 to R.string.title_genre_romance,
        23 to R.string.title_genre_school,
        24 to R.string.title_genre_sci_fi,
        27 to R.string.title_genre_shounen,
        29 to R.string.title_genre_space,
        30 to R.string.title_genre_sports,
        31 to R.string.title_genre_super_power,
        36 to R.string.title_genre_slice_of_life,
        37 to R.string.title_genre_supernatural,
        38 to R.string.title_genre_military,
        39 to R.string.title_genre_detective,
        40 to R.string.title_genre_psychological,
        41 to R.string.title_genre_suspense,
        42 to R.string.title_genre_seinen,
        46 to R.string.title_genre_award_winning,
        47 to R.string.title_genre_gourmet,
        62 to R.string.title_genre_isekai,
        63 to R.string.title_genre_iyashikei,
    )

@Composable
fun scoreText(score: Double): String =
    NumberFormat
        .getNumberInstance(locale())
        .apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(score)

@Composable
fun countText(value: Int): String = NumberFormat.getIntegerInstance(locale()).format(value)

// Line 1 of the airing block without its "to watch" part: "14 of 24 aired", "~14 aired".
@Composable
fun airedText(block: AiringBlock.Aired): String {
    val estimated = block.aired is AiredCount.Estimated
    val total = block.total
    return when {
        total == null && estimated -> stringResource(R.string.title_aired_estimated, block.aired.value)
        total == null -> stringResource(R.string.title_aired, block.aired.value)
        estimated -> stringResource(R.string.title_aired_of_estimated, block.aired.value, total)
        else -> stringResource(R.string.title_aired_of, block.aired.value, total)
    }
}

@Composable
fun toWatchText(toWatch: AiredCount.Known): String =
    when (toWatch) {
        is AiredCount.Estimated -> stringResource(R.string.title_to_watch_estimated, toWatch.value)
        is AiredCount.Exact -> stringResource(R.string.title_to_watch, toWatch.value)
    }

@Composable
fun premiereText(
    premiere: Premiere,
    now: Instant,
): String =
    when (premiere) {
        is Premiere.At -> stringResource(R.string.title_premieres_at, airTime(premiere.instant, now))
        is Premiere.OnDay -> stringResource(R.string.title_premieres_on, pattern(DAY_MONTH_YEAR).format(premiere.date))
        is Premiere.InMonth ->
            stringArrayResource(R.array.title_premieres_in_month)[premiere.yearMonth.monthValue - 1]
                .format(locale(), premiere.yearMonth.year)
        is Premiere.InYear -> stringResource(R.string.title_premieres_in_year, premiere.year.value)
        Premiere.Unannounced -> stringResource(R.string.title_not_yet_aired)
    }

// An air time by the rule of ARCHITECTURE section 10: "today 17:30", "tomorrow 17:30", "Sat 17:30" within the
// next six days, "14 Nov, 17:30" beyond, in the device's zone and 12/24-hour setting.
@Composable
fun airTime(
    at: Instant,
    now: Instant,
): String {
    val zone = ZoneId.systemDefault()
    val day = at.atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), day)
    val hours = if (DateFormat.is24HourFormat(LocalContext.current)) HOURS_24 else HOURS_12
    val time = at.atZone(zone)
    return when (days) {
        0L -> stringResource(R.string.title_today, pattern(hours).format(time))
        1L -> stringResource(R.string.title_tomorrow, pattern(hours).format(time))
        in 2L..SIX_DAYS -> pattern(WEEKDAY + hours).format(time)
        else -> pattern(DAY_MONTH + hours).format(time)
    }
}

@Composable
private fun pattern(skeleton: String): DateTimeFormatter {
    val locale = locale()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}

private const val SIX_DAYS = 6L
private val HOURS_24 = notShown("Hm")
private val HOURS_12 = notShown("hm")
private val WEEKDAY = notShown("E")
private val DAY_MONTH = notShown("dMMM")
private val DAY_MONTH_YEAR = notShown("dMMMy")
