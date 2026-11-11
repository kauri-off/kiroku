package io.github.kauri_off.kiroku.titlesub

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.Source
import io.github.kauri_off.kiroku.core.model.malCodeAsText
import io.github.kauri_off.kiroku.text.clockTime
import io.github.kauri_off.kiroku.text.currentLocale
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

@Composable
internal fun sourceName(source: Source): String =
    when (source) {
        Source.Original -> stringResource(R.string.titlesub_source_original)
        Source.Manga -> stringResource(R.string.titlesub_source_manga)
        Source.FourKomaManga -> stringResource(R.string.titlesub_source_4_koma_manga)
        Source.WebManga -> stringResource(R.string.titlesub_source_web_manga)
        Source.DigitalManga -> stringResource(R.string.titlesub_source_digital_manga)
        Source.Novel -> stringResource(R.string.titlesub_source_novel)
        Source.LightNovel -> stringResource(R.string.titlesub_source_light_novel)
        Source.WebNovel -> stringResource(R.string.titlesub_source_web_novel)
        Source.VisualNovel -> stringResource(R.string.titlesub_source_visual_novel)
        Source.Game -> stringResource(R.string.titlesub_source_game)
        Source.CardGame -> stringResource(R.string.titlesub_source_card_game)
        Source.Book -> stringResource(R.string.titlesub_source_book)
        Source.PictureBook -> stringResource(R.string.titlesub_source_picture_book)
        Source.Radio -> stringResource(R.string.titlesub_source_radio)
        Source.Music -> stringResource(R.string.titlesub_source_music)
        Source.MixedMedia -> stringResource(R.string.titlesub_source_mixed_media)
        Source.Other -> stringResource(R.string.titlesub_source_other)
        is Source.Unrecognized -> malCodeAsText(source.malCode)
    }

@Composable
internal fun ratingName(rating: AgeRating): String =
    when (rating) {
        AgeRating.AllAges -> stringResource(R.string.titlesub_rating_all_ages)
        AgeRating.Children -> stringResource(R.string.titlesub_rating_children)
        AgeRating.Teens13 -> stringResource(R.string.titlesub_rating_teens_13)
        AgeRating.Violence17 -> stringResource(R.string.titlesub_rating_violence_17)
        AgeRating.MildNudity -> stringResource(R.string.titlesub_rating_mild_nudity)
        AgeRating.Hentai -> stringResource(R.string.titlesub_rating_hentai)
        is AgeRating.Unrecognized -> malCodeAsText(rating.malCode)
    }

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
