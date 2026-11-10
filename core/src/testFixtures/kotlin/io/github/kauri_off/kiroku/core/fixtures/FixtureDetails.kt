package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.exactCaughtUp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.exactToWatch
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.notOnListAiring
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime.premiereUnannounced
import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeSeason
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Season
import io.github.kauri_off.kiroku.core.model.Source
import io.github.kauri_off.kiroku.core.model.StatusCounts
import io.github.kauri_off.kiroku.core.model.TitleStatistics
import java.time.Duration
import java.time.LocalDate

object FixtureGenres {
    val action = Genre(1, "Action")
    val adventure = Genre(2, "Adventure")
    val comedy = Genre(4, "Comedy")
    val mystery = Genre(7, "Mystery")
    val drama = Genre(8, "Drama")
    val fantasy = Genre(10, "Fantasy")
    val romance = Genre(22, "Romance")
    val sciFi = Genre(24, "Sci-Fi")
    val sliceOfLife = Genre(36, "Slice of Life")
    val supernatural = Genre(37, "Supernatural")
}

object FixtureDetails {
    private val genreSets =
        listOf(
            listOf(FixtureGenres.action, FixtureGenres.fantasy),
            listOf(FixtureGenres.comedy, FixtureGenres.sliceOfLife),
            listOf(FixtureGenres.drama, FixtureGenres.romance),
            listOf(FixtureGenres.adventure, FixtureGenres.sciFi, FixtureGenres.drama),
            listOf(FixtureGenres.mystery, FixtureGenres.supernatural),
        )

    val related: List<RelatedAnime> =
        listOf(
            RelatedAnime(FixtureAnime.prequel, RelationType.Prequel),
            RelatedAnime(FixtureAnime.premiereYear, RelationType.Sequel),
            RelatedAnime(FixtureAnime.relatedParentStory, RelationType.ParentStory),
            RelatedAnime(FixtureAnime.relatedFullStory, RelationType.FullStory),
            RelatedAnime(FixtureAnime.relatedSideStoryOva, RelationType.SideStory),
            RelatedAnime(FixtureAnime.relatedSideStorySpecial, RelationType.SideStory),
            RelatedAnime(FixtureAnime.relatedSpinOff, RelationType.SpinOff),
            RelatedAnime(FixtureAnime.relatedAlternativeSetting, RelationType.AlternativeSetting),
            RelatedAnime(FixtureAnime.relatedAlternativeVersion, RelationType.AlternativeVersion),
            RelatedAnime(FixtureAnime.relatedSummary, RelationType.Summary),
            RelatedAnime(FixtureAnime.relatedOther, RelationType.Other),
            RelatedAnime(FixtureAnime.relatedCharacter, RelationType.Character),
        )

    val full: AnimeDetails =
        AnimeDetails(
            id = exactToWatch.id,
            synopsis =
                "The second year at the old prefectural library begins with a heat haze that will not lift. " +
                    "Shiori Amane, now the youngest full-time librarian, notices that the books returned on the " +
                    "hottest afternoons come back with pages that were never printed. With her senior colleague " +
                    "and a high-school volunteer who can read those pages aloud, she follows the stories to the " +
                    "people who lost them, while the library itself is scheduled to close at the end of the summer.",
            genres = listOf(FixtureGenres.drama, FixtureGenres.mystery, FixtureGenres.supernatural),
            endDate = PartialDate.Day(LocalDate.of(2026, 3, 28)),
            season = AnimeSeason(2025, Season.Fall),
            source = Source.LightNovel,
            studios = listOf("Studio Hanamizuki", "Kouyou Animation"),
            rating = AgeRating.Teens13,
            episodeDuration = Duration.ofMinutes(23).plusSeconds(40),
            statistics =
                TitleStatistics(
                    statuses =
                        StatusCounts(
                            watching = 182_345,
                            completed = 1_208,
                            onHold = 8_211,
                            dropped = 3_456,
                            planned = 95_432,
                        ),
                    members = 290_652,
                ),
            related = related,
            recommendations = listOf(exactCaughtUp, notOnListAiring),
        )

    val sparse: AnimeDetails =
        AnimeDetails(
            id = premiereUnannounced.id,
            synopsis = null,
            genres = emptyList(),
            endDate = null,
            season = null,
            source = null,
            studios = emptyList(),
            rating = null,
            episodeDuration = null,
            statistics = TitleStatistics(StatusCounts(0, 0, 0, 0, 1_284), members = 1_284),
            related = emptyList(),
            recommendations = emptyList(),
        )

    val all: List<AnimeDetails> =
        FixtureAnime.all.map { anime ->
            when (anime.id) {
                full.id -> full
                sparse.id -> sparse
                else -> plain(anime)
            }
        }

    private fun plain(anime: Anime): AnimeDetails {
        val index = (anime.id.value % genreSets.size).toInt()
        val start = anime.startDate
        return AnimeDetails(
            id = anime.id,
            synopsis =
                "${anime.titles.english ?: anime.titles.romaji} follows a small cast through one season of " +
                    "quiet changes. Fixture text for previews and demo mode.",
            genres = genreSets[index],
            endDate = if (anime.airingStatus == AiringStatus.Finished) start else null,
            season = (start as? PartialDate.Day)?.date?.let(::seasonOf),
            source = if (anime.mediaType == MediaType.Tv) Source.Manga else Source.Original,
            studios = listOf("Studio Hanamizuki"),
            rating = AgeRating.Teens13,
            episodeDuration = if (anime.mediaType == MediaType.Movie) Duration.ofMinutes(108) else Duration.ofMinutes(24),
            statistics = statistics(anime),
            related = emptyList(),
            recommendations = emptyList(),
        )
    }

    private fun seasonOf(date: LocalDate): AnimeSeason =
        AnimeSeason(
            date.year,
            when (date.monthValue) {
                in 1..3 -> Season.Winter
                in 4..6 -> Season.Spring
                in 7..9 -> Season.Summer
                else -> Season.Fall
            },
        )

    private fun statistics(anime: Anime): TitleStatistics {
        val members = 2_400_000 / (1 + (anime.rank ?: 20_000) / 150)
        val counts =
            when (anime.airingStatus) {
                AiringStatus.NotYetAired -> StatusCounts(0, 0, 0, 0, members)
                AiringStatus.Airing ->
                    StatusCounts(
                        watching = members * 58 / 100,
                        completed = members / 100,
                        onHold = members * 4 / 100,
                        dropped = members * 5 / 100,
                        planned = members - members * 68 / 100,
                    )
                AiringStatus.Finished ->
                    StatusCounts(
                        watching = members * 4 / 100,
                        completed = members * 70 / 100,
                        onHold = members * 3 / 100,
                        dropped = members * 4 / 100,
                        planned = members - members * 81 / 100,
                    )
            }
        return TitleStatistics(counts, counts.total)
    }
}
