package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.Counted
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.StatusCounts
import io.github.kauri_off.kiroku.core.model.UserStatistics

object FixtureUser {
    val statistics: UserStatistics =
        UserStatistics(
            statuses = StatusCounts(watching = 14, completed = 412, onHold = 9, dropped = 23, planned = 138),
            daysWatched = 186.4,
            meanScore = 7.62,
            scores = mapOf(1 to 1, 2 to 2, 3 to 4, 4 to 9, 5 to 21, 6 to 48, 7 to 102, 8 to 121, 9 to 64, 10 to 18),
            genres =
                listOf(
                    Counted(FixtureGenres.comedy, 182),
                    Counted(FixtureGenres.drama, 164),
                    Counted(FixtureGenres.action, 141),
                    Counted(FixtureGenres.fantasy, 118),
                    Counted(FixtureGenres.sliceOfLife, 96),
                    Counted(FixtureGenres.romance, 87),
                    Counted(FixtureGenres.supernatural, 61),
                    Counted(FixtureGenres.mystery, 44),
                    Counted(FixtureGenres.sciFi, 39),
                    Counted(FixtureGenres.adventure, 33),
                ),
            mediaTypes =
                listOf(
                    Counted(MediaType.Tv, 431),
                    Counted(MediaType.Movie, 64),
                    Counted(MediaType.Ova, 38),
                    Counted(MediaType.Ona, 31),
                    Counted(MediaType.Special, 26),
                    Counted(MediaType.Music, 6),
                ),
            years =
                mapOf(
                    2008 to 6,
                    2010 to 9,
                    2012 to 14,
                    2014 to 22,
                    2016 to 31,
                    2017 to 38,
                    2018 to 44,
                    2019 to 52,
                    2020 to 49,
                    2021 to 61,
                    2022 to 67,
                    2023 to 72,
                    2024 to 81,
                    2025 to 50,
                ),
        )

    val otherUserEntries: List<ListEntry> =
        listOf(
            other(FixtureAnime.exactToWatch, ListStatus.Watching, 9, null),
            other(FixtureAnime.longTitle, ListStatus.Watching, 3, null),
            other(FixtureAnime.unknownTotal, ListStatus.Watching, 1050, null),
            other(FixtureAnime.prequel, ListStatus.Completed, 24, 10),
            other(FixtureAnime.film, ListStatus.Completed, 1, null),
            other(FixtureAnime.plannedFinished, ListStatus.Planned, 0, null),
        )

    private fun other(
        anime: Anime,
        status: ListStatus,
        watched: Int,
        score: Int?,
    ) = ListEntry(
        anime = anime,
        values = ListValues(status, watched, score, null, null),
        updatedAt = FixtureTime.moscow("2025-12-01T12:00"),
    )
}
