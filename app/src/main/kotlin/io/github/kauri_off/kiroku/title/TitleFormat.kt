package io.github.kauri_off.kiroku.title

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.malCodeAsText
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.text.AirTimeText
import java.text.NumberFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
@ReadOnlyComposable
private fun locale(): Locale = LocalConfiguration.current.locales[0]

// "TV · 2024 · 24 episodes", from the facts the state chose.
@Composable
fun factsLine(facts: HeaderFacts): String =
    listOfNotNull(
        facts.type?.let { mediaTypeLabel(it) },
        facts.year?.toString(),
        facts.episodes?.let { pluralStringResource(R.plurals.title_episodes, it, it) },
    ).joinToString(stringResource(R.string.title_separator))

// "TV · 2019", for a related title.
@Composable
fun typeAndYear(anime: Anime): String = factsLine(HeaderFacts(anime.mediaType, anime.startDate?.year, episodes = null))

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
fun scoreText(score: Double): String =
    NumberFormat
        .getNumberInstance(locale())
        .apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(score)

@Composable
fun countText(value: Int): String = NumberFormat.getIntegerInstance(locale()).format(value)

// Line 1 of the airing block without its "to watch" part: "14 aired", "~14 aired". The total is stated elsewhere.
@Composable
fun airedText(aired: AiredCount.Known): String =
    when (aired) {
        is AiredCount.Estimated -> stringResource(R.string.title_aired_estimated, aired.value)
        is AiredCount.Exact -> stringResource(R.string.title_aired, aired.value)
    }

@Composable
fun toWatchText(toWatch: AiredCount.Known): String =
    when (toWatch) {
        is AiredCount.Estimated -> stringResource(R.string.title_to_watch_estimated, toWatch.value)
        is AiredCount.Exact -> stringResource(R.string.title_to_watch, toWatch.value)
    }

// A premiere with the year of a full date, as the title page writes it.
@Composable
fun premiereText(
    premiere: Premiere,
    times: AirTimeText,
): String =
    when (premiere) {
        is Premiere.At -> stringResource(R.string.title_premieres_at, times.airTime(premiere.instant))
        is Premiere.OnDay -> stringResource(R.string.title_premieres_on, dayMonthYear().format(premiere.date))
        is Premiere.InMonth ->
            stringArrayResource(R.array.title_premieres_in_month)[premiere.yearMonth.monthValue - 1]
                .format(locale(), premiere.yearMonth.year)
        is Premiere.InYear -> stringResource(R.string.title_premieres_in_year, premiere.year.value)
        Premiere.Unannounced -> stringResource(R.string.title_not_yet_aired)
    }

// The page's air times, in the zone of the state's clock.
@Composable
fun rememberAirTimeText(now: ZonedDateTime): AirTimeText {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration, now) { AirTimeText(context, now.toInstant(), now.zone) }
}

@Composable
private fun dayMonthYear(): DateTimeFormatter {
    val locale = locale()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, DAY_MONTH_YEAR), locale)
}

private val DAY_MONTH_YEAR = notShown("dMMMy")
