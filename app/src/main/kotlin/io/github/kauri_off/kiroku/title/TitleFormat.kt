package io.github.kauri_off.kiroku.title

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.text.AirTimeText
import io.github.kauri_off.kiroku.text.currentLocale
import io.github.kauri_off.kiroku.text.premiereText
import io.github.kauri_off.kiroku.text.relationName
import io.github.kauri_off.kiroku.text.typeAndYear
import java.text.NumberFormat
import java.time.ZonedDateTime

// "TV · 2024 · 24 episodes", from the facts the state chose.
@Composable
fun factsLine(facts: HeaderFacts): String =
    listOfNotNull(
        typeAndYear(facts.type, facts.year).takeIf { it.isNotEmpty() },
        facts.episodes?.let { pluralStringResource(R.plurals.title_episodes, it, it) },
    ).joinToString(stringResource(R.string.common_separator))

@Composable
fun typeAndYear(anime: Anime): String = typeAndYear(anime.mediaType, anime.startDate?.year)

// The relation as caption 1 of a strip card, with the short labels of the two longest names.
@Composable
fun RelationType.stripLabel(): String? =
    when (this) {
        RelationType.AlternativeSetting -> stringResource(R.string.title_relation_alt_setting)
        RelationType.AlternativeVersion -> stringResource(R.string.title_relation_alt_version)
        RelationType.Other, RelationType.Character, is RelationType.Unrecognized -> null
        else -> relationName(this)
    }

@Composable
fun scoreText(score: Double): String =
    NumberFormat
        .getNumberInstance(currentLocale())
        .apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(score)

@Composable
fun countText(value: Int): String = NumberFormat.getIntegerInstance(currentLocale()).format(value)

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
): String = premiereText(LocalContext.current, times, premiere, withYear = true)

// The page's air times, in the zone of the state's clock.
@Composable
fun rememberAirTimeText(now: ZonedDateTime): AirTimeText {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration, now) { AirTimeText(context, now.toInstant(), now.zone) }
}
