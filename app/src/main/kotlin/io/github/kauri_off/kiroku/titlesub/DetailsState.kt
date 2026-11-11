package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.Source
import io.github.kauri_off.kiroku.notShown
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

sealed interface DetailsState {
    data object Loading : DetailsState

    data class Failed(
        val error: DataError,
    ) : DetailsState

    // Facts in the order of ARCHITECTURE section 9.9; a fact MAL does not give is absent.
    data class Shown(
        val facts: List<Fact>,
    ) : DetailsState
}

sealed interface Fact {
    data class EnglishTitle(
        val title: String,
    ) : Fact

    data class JapaneseTitle(
        val title: String,
    ) : Fact

    data class Synonyms(
        val titles: List<String>,
    ) : Fact

    data class StartDate(
        val date: PartialDate,
    ) : Fact

    data class EndDate(
        val date: PartialDate,
    ) : Fact

    data class SourceFact(
        val source: Source,
    ) : Fact

    data class Studios(
        val names: List<String>,
    ) : Fact

    data class Rating(
        val rating: AgeRating,
    ) : Fact

    data class EpisodeLength(
        val length: Duration,
    ) : Fact

    // The weekly slot in the device's zone; the time is absent when MAL gives only the day.
    data class Broadcast(
        val day: DayOfWeek,
        val time: LocalTime?,
    ) : Fact
}

sealed interface DetailsEvent {
    data object Back : DetailsEvent

    data object Retry : DetailsEvent
}

fun detailsState(
    anime: Anime,
    details: AnimeDetails,
    clock: Clock,
): DetailsState.Shown {
    val titles = anime.titles
    return DetailsState.Shown(
        listOfNotNull(
            titles.english?.let(Fact::EnglishTitle),
            titles.japanese?.let(Fact::JapaneseTitle),
            titles.synonyms.takeIf { it.isNotEmpty() }?.let(Fact::Synonyms),
            anime.startDate?.let(Fact::StartDate),
            details.endDate?.let(Fact::EndDate),
            details.source?.let(Fact::SourceFact),
            details.studios.takeIf { it.isNotEmpty() }?.let(Fact::Studios),
            details.rating?.let(Fact::Rating),
            anime.averageEpisodeDuration?.let(Fact::EpisodeLength),
            anime.broadcast?.let { broadcast ->
                val time = broadcast.japanTime
                if (time == null) {
                    Fact.Broadcast(broadcast.dayOfWeek, null)
                } else {
                    val local = nextInJapan(broadcast.dayOfWeek, time, clock).withZoneSameInstant(clock.zone)
                    Fact.Broadcast(local.dayOfWeek, local.toLocalTime())
                }
            },
        ),
    )
}

// The slot's next occurrence, so that a daylight-saving offset of the device's zone applies as it will next air.
private fun nextInJapan(
    day: DayOfWeek,
    time: LocalTime,
    clock: Clock,
): ZonedDateTime {
    val now = clock.instant().atZone(JAPAN)
    val candidate = now.with(TemporalAdjusters.nextOrSame(day)).with(time)
    return if (candidate.isBefore(now)) candidate.plusWeeks(1) else candidate
}

private val JAPAN: ZoneId = ZoneId.of(notShown("Asia/Tokyo"))
