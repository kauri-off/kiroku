package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.airing.nextStart
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.Source
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

// Every state has the top bar, so every state shows the session banner while the session is expired (S6).
sealed interface DetailsState {
    val session: Session

    data class Loading(
        override val session: Session,
    ) : DetailsState

    data class Failed(
        val error: DataError,
        override val session: Session,
    ) : DetailsState

    // Facts in the order of ARCHITECTURE section 9.9; a fact MAL does not give is absent.
    data class Shown(
        val facts: List<Fact>,
        override val session: Session,
    ) : DetailsState
}

val DetailsState.sessionExpired: Boolean get() = session == Session.Expired

sealed interface Fact {
    data class RomajiTitle(
        val title: String,
    ) : Fact

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

    data object SignIn : DetailsEvent
}

// The titles other than the one "Title language" shows as the main title, then the facts MAL gives. A finished
// title has no broadcast time, as its weekly slot no longer runs.
fun detailsState(
    anime: Anime,
    details: AnimeDetails,
    language: TitleLanguage,
    session: Session,
    clock: Clock,
): DetailsState.Shown {
    val titles = anime.titles
    val main = titles.shownLanguage(language)
    return DetailsState.Shown(
        listOfNotNull(
            titles.romaji.takeIf { main != TitleLanguage.Romaji }?.let(Fact::RomajiTitle),
            titles.english?.takeIf { main != TitleLanguage.English }?.let(Fact::EnglishTitle),
            titles.japanese?.takeIf { main != TitleLanguage.Japanese }?.let(Fact::JapaneseTitle),
            titles.synonyms.takeIf { it.isNotEmpty() }?.let(Fact::Synonyms),
            anime.startDate?.let(Fact::StartDate),
            details.endDate?.let(Fact::EndDate),
            details.source?.let(Fact::SourceFact),
            details.studios.takeIf { it.isNotEmpty() }?.let(Fact::Studios),
            details.rating?.let(Fact::Rating),
            anime.averageEpisodeDuration?.let(Fact::EpisodeLength),
            anime.broadcast?.takeIf { anime.airingStatus != AiringStatus.Finished }?.let { broadcast ->
                val start = broadcast.nextStart(clock.instant())?.atZone(clock.zone)
                if (start == null) {
                    Fact.Broadcast(broadcast.dayOfWeek, null)
                } else {
                    Fact.Broadcast(start.dayOfWeek, start.toLocalTime())
                }
            },
        ),
        session,
    )
}
