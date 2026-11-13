package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.model.UserStatistics
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

data class FixtureSet(
    val clock: Clock,
    val anime: List<Anime>,
    val entries: List<ListEntry>,
    val schedules: Map<AnimeId, AiringSchedule>,
    // A snapshot at the set's creation; nothing recomputes it as a clock runs.
    val airing: Map<AnimeId, Airing>,
    val details: Map<AnimeId, AnimeDetails>,
    val user: UserProfile,
    val userStatistics: UserStatistics,
    val otherUserName: String,
    val otherUserEntries: List<ListEntry>,
    val privateUserName: String,
    // A statistics layout state, independent of otherUserEntries.
    val largeStatistics: UserStatistics,
) {
    val now: Instant get() = clock.instant()

    val session: Session.SignedIn get() = Session.SignedIn(SignInId("demo"))

    val expired: Session.Expired get() = Session.Expired(session.signIn)

    fun anime(id: AnimeId): Anime = anime.single { it.id == id }

    fun entryFor(id: AnimeId): ListEntry? = entries.firstOrNull { it.anime.id == id }

    internal fun shiftedBy(weeks: Long): FixtureSet {
        if (weeks == 0L) return this
        val shift = WeekShift(weeks)
        val entries = entries.map(shift::entry)
        val anime = anime.map(shift::anime)
        val shiftedById = anime.associateBy { it.id }
        val today = now.atZone(FixtureTime.zone).toLocalDate()
        val years = shift.date(today).year - today.year
        return copy(
            anime = anime,
            entries = entries,
            schedules = schedules.mapValues { shift.schedule(it.value) },
            airing = airing.mapValues { shift.airing(it.value) },
            details = details.mapValues { (id, value) -> shift.details(value, shiftedById.getValue(id)) },
            user = user.copy(joined = user.joined?.plusWeeks(weeks)),
            userStatistics = userStatistics.copy(years = startYears(entries)),
            otherUserEntries = otherUserEntries.map(shift::entry),
            largeStatistics = largeStatistics.copy(years = largeStatistics.years.mapKeys { it.key + years }),
        )
    }

    companion object {
        fun startYears(entries: List<ListEntry>): Map<Int, Int> =
            entries
                .mapNotNull { it.anime.startDate?.year }
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
    }
}

private class WeekShift(
    private val weeks: Long,
) {
    private val duration: Duration = Duration.ofDays(7 * weeks)

    fun instant(value: Instant): Instant = value.plus(duration)

    fun date(value: LocalDate): LocalDate = value.plusWeeks(weeks)

    fun date(value: PartialDate): PartialDate =
        when (value) {
            is PartialDate.Day -> PartialDate.Day(date(value.date))
            is PartialDate.Month -> PartialDate.Month(YearMonth.from(date(value.yearMonth.atDay(1))))
            is PartialDate.OnlyYear -> PartialDate.OnlyYear(Year.from(date(value.value.atDay(1))))
        }

    fun anime(value: Anime): Anime = value.copy(startDate = value.startDate?.let(::date))

    fun entry(value: ListEntry): ListEntry =
        value.copy(
            anime = anime(value.anime),
            values =
                value.values.copy(
                    startDate = value.values.startDate?.let(::date),
                    finishDate = value.values.finishDate?.let(::date),
                ),
            updatedAt = instant(value.updatedAt),
        )

    fun episode(value: ScheduledEpisode): ScheduledEpisode = value.copy(airsAt = instant(value.airsAt))

    fun schedule(value: AiringSchedule): AiringSchedule = value.copy(episodes = value.episodes.map(::episode))

    fun airing(value: Airing): Airing =
        value.copy(
            nextEpisode = value.nextEpisode?.let(::episode),
            latestAiredAt = value.latestAiredAt?.let(::instant),
        )

    fun details(
        value: AnimeDetails,
        anime: Anime,
    ): AnimeDetails =
        value.copy(
            endDate = value.endDate?.let(::date),
            season = (anime.startDate as? PartialDate.Day)?.date?.let(FixtureDetails::seasonOf),
            related = value.related.map { RelatedAnime(anime(it.anime), it.relation) },
            recommendations = value.recommendations.map(::anime),
        )
}
