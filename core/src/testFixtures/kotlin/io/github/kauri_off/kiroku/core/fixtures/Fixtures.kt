package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.airedUnknown
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.completedOna
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.completedOva
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.completedSpecial
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.completedUnscored
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.droppedEstimated
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.droppedFinished
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.droppedMovie
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.estimatedCaughtUp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.estimatedToWatch
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.exactCaughtUp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.exactCaughtUpLater
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.exactOnBreak
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.exactToWatch
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.film
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.finishedPartWay
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.longTitle
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.notOnListAiring
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.onHoldAiring
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.onHoldFinished
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.onHoldOva
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.oneShortOfTotal
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.plannedAiring
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.plannedFinished
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.premiereDate
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.premiereMonth
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.premiereTimeLater
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.premiereTimeSoon
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.premiereUnannounced
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.premiereYear
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.prequel
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.unknownTotal
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime.japan
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime.moscow
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime.weekly
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.UserProfile
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

object Fixtures {
    val reference: FixtureSet by lazy {
        FixtureSet(
            clock = FixtureTime.clock,
            anime = FixtureAnime.all,
            entries = entries,
            schedules = schedules,
            airing = airing,
            details = FixtureDetails.all.associateBy { it.id },
            user = UserProfile(name = "kiroku_demo", pictureUrl = null, joined = LocalDate.of(2019, 3, 2)),
            userStatistics = FixtureUser.statistics,
            otherUserName = "sample_viewer",
            otherUserEntries = FixtureUser.otherUserEntries,
            privateUserName = "quiet_viewer",
            largeStatistics = FixtureUser.largeStatistics,
        )
    }

    fun at(clock: Clock): FixtureSet {
        val elapsed = Duration.between(FixtureTime.reference, clock.instant())
        val weeks = Math.floorDiv(elapsed.seconds, Duration.ofDays(7).seconds)
        val lag = elapsed.minusDays(7 * weeks)
        return reference.shiftedBy(weeks).copy(clock = Clock.offset(clock, lag.negated()))
    }

    private val entries: List<ListEntry> =
        listOf(
            entry(exactToWatch, ListStatus.Watching, 12, score = 8, start = "2025-10-12", updated = "2026-01-11T21:40"),
            entry(exactCaughtUp, ListStatus.Watching, 8, start = "2025-11-20", updated = "2026-01-08T22:10"),
            entry(exactCaughtUpLater, ListStatus.Watching, 3, start = "2025-12-30", updated = "2026-01-13T08:05"),
            entry(exactOnBreak, ListStatus.Watching, 6, start = "2025-12-06", updated = "2026-01-10T20:05"),
            entry(estimatedToWatch, ListStatus.Watching, 7, start = "2025-11-06", updated = "2025-12-27T19:00"),
            entry(estimatedCaughtUp, ListStatus.Watching, 5, start = "2025-12-13", updated = "2026-01-12T18:30"),
            entry(airedUnknown, ListStatus.Watching, 212, start = "2023-02-01", updated = "2026-01-04T11:15"),
            entry(unknownTotal, ListStatus.Watching, 1118, start = "2016-05-14", updated = "2026-01-05T09:20"),
            entry(finishedPartWay, ListStatus.Watching, 10, start = "2025-11-30", updated = "2025-12-20T23:45"),
            entry(oneShortOfTotal, ListStatus.Watching, 11, start = "2026-01-03", updated = "2026-01-13T22:50"),
            entry(longTitle, ListStatus.Watching, 1, start = "2025-12-26", updated = "2026-01-02T00:30"),
            entry(premiereTimeSoon, ListStatus.Planned, 0, updated = "2025-12-30T14:00"),
            entry(premiereTimeLater, ListStatus.Planned, 0, updated = "2025-12-18T10:30"),
            entry(premiereDate, ListStatus.Planned, 0, updated = "2025-11-22T16:45"),
            entry(premiereMonth, ListStatus.Planned, 0, updated = "2025-10-09T08:15"),
            entry(premiereYear, ListStatus.Planned, 0, updated = "2025-09-28T20:00"),
            entry(premiereUnannounced, ListStatus.Planned, 0, updated = "2025-08-14T12:10"),
            entry(plannedAiring, ListStatus.Planned, 0, updated = "2026-01-06T19:25"),
            entry(plannedFinished, ListStatus.Planned, 0, updated = "2024-12-01T09:00"),
            entry(
                prequel,
                ListStatus.Completed,
                24,
                score = 9,
                start = "2023-04-08",
                finish = "2023-09-23",
                updated = "2023-09-23T23:10",
            ),
            entry(film, ListStatus.Completed, 1, score = 8, finish = "2025-03-02", updated = "2025-03-02T22:00"),
            entry(completedUnscored, ListStatus.Completed, 12, finish = "2025-06-11", updated = "2025-06-11T21:30"),
            entry(completedOva, ListStatus.Completed, 4, score = 10, updated = "2024-07-19T18:05"),
            entry(completedOna, ListStatus.Completed, 8, score = 6, updated = "2025-11-02T13:40"),
            entry(completedSpecial, ListStatus.Completed, 1, score = 7, updated = "2025-01-02T10:20"),
            entry(onHoldAiring, ListStatus.OnHold, 15, start = "2025-08-29", updated = "2025-12-12T23:55"),
            entry(onHoldFinished, ListStatus.OnHold, 3, updated = "2024-04-21T17:00"),
            entry(onHoldOva, ListStatus.OnHold, 1, updated = "2023-01-15T15:15"),
            entry(droppedFinished, ListStatus.Dropped, 2, score = 4, updated = "2024-10-20T22:35"),
            entry(droppedEstimated, ListStatus.Dropped, 4, updated = "2025-12-08T20:20"),
            entry(droppedMovie, ListStatus.Dropped, 0, updated = "2023-07-01T11:00"),
        )

    private val schedules: Map<AnimeId, AiringSchedule> =
        mapOf(
            exactToWatch.id to AiringSchedule(weekly(15, japan("2026-01-17T23:30"), 1..24), 24),
            exactCaughtUp.id to AiringSchedule(weekly(9, japan("2026-01-15T00:00"), 1..12), 12),
            exactCaughtUpLater.id to AiringSchedule(weekly(1, japan("2025-12-29T23:00"), 1..12), 12),
            exactOnBreak.id to AiringSchedule(weekly(6, japan("2026-01-09T22:00"), 1..6), 13),
            unknownTotal.id to AiringSchedule(weekly(1122, japan("2026-01-17T18:00"), 1115..1122), null),
            longTitle.id to AiringSchedule(weekly(4, japan("2026-01-16T01:00"), 1..13), 13),
            premiereTimeSoon.id to AiringSchedule(weekly(1, japan("2026-01-18T00:30"), 1..12), 12),
            premiereTimeLater.id to AiringSchedule(weekly(1, japan("2026-01-28T21:00"), 1..10), 10),
            plannedAiring.id to AiringSchedule(weekly(6, japan("2026-01-16T23:00"), 1..12), 12),
            onHoldAiring.id to AiringSchedule(weekly(21, japan("2026-01-15T23:00"), 1..24), 24),
            notOnListAiring.id to AiringSchedule(weekly(8, japan("2026-01-18T22:00"), 1..12), 12),
        )

    private val airing: Map<AnimeId, Airing> =
        mapOf(
            exactToWatch.id to exact(exactToWatch, aired = 14, next = 15),
            exactCaughtUp.id to exact(exactCaughtUp, aired = 8, next = 9),
            exactCaughtUpLater.id to exact(exactCaughtUpLater, aired = 3, next = 4),
            exactOnBreak.id to exact(exactOnBreak, aired = 6, next = null),
            unknownTotal.id to exact(unknownTotal, aired = 1121, next = 1122),
            longTitle.id to exact(longTitle, aired = 3, next = 4),
            premiereTimeSoon.id to exact(premiereTimeSoon, aired = 0, next = 1),
            premiereTimeLater.id to exact(premiereTimeLater, aired = 0, next = 1),
            plannedAiring.id to exact(plannedAiring, aired = 5, next = 6),
            onHoldAiring.id to exact(onHoldAiring, aired = 20, next = 21),
            notOnListAiring.id to exact(notOnListAiring, aired = 7, next = 8),
            estimatedToWatch.id to Airing(AiredCount.Estimated(10), 12, null, japan("2026-01-07T22:00")),
            estimatedCaughtUp.id to Airing(AiredCount.Estimated(5), 12, null, japan("2026-01-10T01:30")),
            droppedEstimated.id to Airing(AiredCount.Estimated(9), 12, null, japan("2026-01-09T00:00")),
            airedUnknown.id to Airing(AiredCount.Unknown, null, null, null),
        )

    private fun exact(
        anime: Anime,
        aired: Int,
        next: Int?,
    ): Airing {
        val schedule = schedules.getValue(anime.id)
        val episodes = schedule.episodes.associateBy { it.number }
        return Airing(
            aired = AiredCount.Exact(aired),
            total = anime.malEpisodes ?: schedule.totalEpisodes,
            nextEpisode = next?.let(episodes::getValue),
            latestAiredAt = episodes[aired]?.airsAt,
        )
    }

    private fun entry(
        anime: Anime,
        status: ListStatus,
        watched: Int,
        score: Int? = null,
        start: String? = null,
        finish: String? = null,
        updated: String,
    ) = ListEntry(
        anime = anime,
        values =
            ListValues(
                status = status,
                watchedEpisodes = watched,
                score = score,
                startDate = start?.let { PartialDate.Day(LocalDate.parse(it)) },
                finishDate = finish?.let { PartialDate.Day(LocalDate.parse(it)) },
            ),
        updatedAt = moscow(updated),
    )
}
