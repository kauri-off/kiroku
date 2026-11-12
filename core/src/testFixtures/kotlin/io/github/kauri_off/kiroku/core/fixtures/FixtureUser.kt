package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.Counted
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.StatusCounts
import io.github.kauri_off.kiroku.core.model.UserStatistics
import io.github.kauri_off.kiroku.core.fixtures.FixtureGenres as G

object FixtureUser {
    val statistics: UserStatistics =
        UserStatistics(
            statuses = StatusCounts(watching = 11, completed = 6, onHold = 3, dropped = 3, planned = 8),
            daysWatched = 24.4,
            meanScore = 7.43,
            scores = mapOf(4 to 1, 6 to 1, 7 to 1, 8 to 2, 9 to 1, 10 to 1),
            genres =
                listOf(
                    Counted(G.fantasy, 12),
                    Counted(G.comedy, 10),
                    Counted(G.drama, 10),
                    Counted(G.romance, 8),
                    Counted(G.sliceOfLife, 6),
                    Counted(G.mystery, 6),
                    Counted(G.action, 5),
                    Counted(G.adventure, 4),
                    Counted(G.supernatural, 4),
                    Counted(G.iyashikei, 4),
                    Counted(G.sciFi, 3),
                    Counted(G.gourmet, 3),
                    Counted(G.mecha, 2),
                    Counted(G.music, 2),
                    Counted(G.school, 2),
                    Counted(G.military, 2),
                    Counted(G.detective, 2),
                    Counted(G.isekai, 2),
                    Counted(G.historical, 1),
                    Counted(G.shounen, 1),
                    Counted(G.superPower, 1),
                    Counted(G.psychological, 1),
                    Counted(G.awardWinning, 1),
                ),
            mediaTypes =
                listOf(
                    Counted(MediaType.Tv, 23),
                    Counted(MediaType.Movie, 2),
                    Counted(MediaType.Ona, 2),
                    Counted(MediaType.Ova, 2),
                    Counted(MediaType.Special, 1),
                ),
            years =
                mapOf(
                    2004 to 1,
                    2017 to 1,
                    2018 to 1,
                    2019 to 2,
                    2020 to 1,
                    2021 to 1,
                    2022 to 2,
                    2023 to 2,
                    2024 to 2,
                    2025 to 12,
                    2026 to 4,
                    2027 to 1,
                ),
        )

    val largeStatistics: UserStatistics =
        UserStatistics(
            statuses = StatusCounts(watching = 17, completed = 2_384, onHold = 41, dropped = 163, planned = 9_807),
            daysWatched = 638.7,
            meanScore = 7.32,
            scores = mapOf(1 to 3, 2 to 7, 3 to 18, 4 to 42, 5 to 131, 6 to 309, 7 to 602, 8 to 711, 9 to 288, 10 to 76),
            genres =
                listOf(
                    Counted(G.comedy, 6_418),
                    Counted(G.action, 5_902),
                    Counted(G.fantasy, 4_377),
                    Counted(G.drama, 3_861),
                    Counted(G.romance, 3_054),
                    Counted(G.sciFi, 2_219),
                    Counted(G.sliceOfLife, 2_107),
                    Counted(G.supernatural, 1_986),
                    Counted(G.adventure, 1_843),
                    Counted(G.mystery, 1_212),
                    Counted(G.school, 1_198),
                    Counted(G.shounen, 1_105),
                    Counted(G.mecha, 874),
                    Counted(G.psychological, 702),
                    Counted(G.historical, 655),
                    Counted(G.music, 513),
                    Counted(G.sports, 497),
                    Counted(G.isekai, 466),
                    Counted(G.military, 431),
                    Counted(G.seinen, 402),
                    Counted(G.horror, 318),
                    Counted(G.suspense, 276),
                    Counted(G.space, 154),
                    Counted(G.avantGarde, 38),
                ),
            mediaTypes =
                listOf(
                    Counted(MediaType.Tv, 7_644),
                    Counted(MediaType.Movie, 1_352),
                    Counted(MediaType.Ova, 1_207),
                    Counted(MediaType.Special, 1_033),
                    Counted(MediaType.Ona, 852),
                    Counted(MediaType.Music, 219),
                    Counted(MediaType.TvSpecial, 105),
                ),
            years =
                mapOf(
                    1979 to 7,
                    1980 to 7,
                    1981 to 9,
                    1982 to 12,
                    1983 to 11,
                    1984 to 11,
                    1985 to 15,
                    1986 to 18,
                    1987 to 17,
                    1988 to 18,
                    1989 to 25,
                    1990 to 27,
                    1991 to 25,
                    1992 to 31,
                    1993 to 40,
                    1994 to 40,
                    1995 to 39,
                    1996 to 52,
                    1997 to 63,
                    1998 to 58,
                    1999 to 63,
                    2000 to 86,
                    2001 to 94,
                    2002 to 87,
                    2003 to 104,
                    2004 to 138,
                    2005 to 139,
                    2006 to 133,
                    2007 to 174,
                    2008 to 215,
                    2009 to 203,
                    2010 to 212,
                    2011 to 288,
                    2012 to 326,
                    2013 to 299,
                    2014 to 348,
                    2015 to 466,
                    2016 to 482,
                    2017 to 453,
                    2018 to 580,
                    2019 to 734,
                    2020 to 704,
                    2021 to 713,
                    2022 to 963,
                    2023 to 1_120,
                    2024 to 1_031,
                    2025 to 1_183,
                    2026 to 549,
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

    // Longer than MAL allows a user name to be, so no real user has one.
    val unknownName: String = "nobody_by_that_name"

    // Names opened earlier for the Profile's "Open another profile", newest first.
    val openedNames: List<String> = listOf("sample_viewer", "quiet_viewer", "kiroku_guest_one", "kiroku_guest_two", "kiroku_guest_three")

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
