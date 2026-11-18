package io.github.kauri_off.kiroku.core.statistics

import io.github.kauri_off.kiroku.core.fixtures.FixtureGenres
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Counted
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.StatusCounts
import io.github.kauri_off.kiroku.core.model.Titles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.Year

class StatisticsTest {
    private val action = FixtureGenres.action
    private val comedy = FixtureGenres.comedy
    private val drama = FixtureGenres.drama
    private val supernatural = FixtureGenres.supernatural
    private val military = FixtureGenres.military

    private var nextId = 1L

    private fun anime(
        type: MediaType? = MediaType.Tv,
        minutes: Long? = 24,
        episodes: Int? = 12,
        year: Int? = 2020,
        genres: List<Genre>? = emptyList(),
        seconds: Long? = null,
    ) = Anime(
        id = AnimeId(nextId++),
        titles = Titles("Title $nextId"),
        posterUrl = null,
        largePosterUrl = null,
        mediaType = type,
        airingStatus = AiringStatus.Finished,
        malEpisodes = episodes,
        startDate = year?.let { PartialDate.OnlyYear(Year.of(it)) },
        broadcast = null,
        meanScore = null,
        rank = null,
        genres = genres,
        averageEpisodeDuration = seconds?.let(Duration::ofSeconds) ?: minutes?.let(Duration::ofMinutes),
    )

    private fun entry(
        anime: Anime,
        status: EntryStatus = ListStatus.Completed,
        watched: Int = 0,
        score: Int? = null,
    ) = ListEntry(anime, ListValues(status, watched, score, null, null), Instant.EPOCH)

    private val none = StatusCounts(0, 0, 0, 0, 0)

    @Test
    fun `a small list's every value is the one worked out by hand`() {
        val statistics =
            statisticsOf(
                listOf(
                    entry(anime(MediaType.Tv, 24, 12, 2020, listOf(action, comedy)), ListStatus.Completed, 12, 8),
                    entry(anime(MediaType.Movie, 120, 1, 2019, listOf(military)), ListStatus.Completed, 1, 10),
                    entry(anime(MediaType.Tv, 24, 24, 2020, listOf(comedy, supernatural)), ListStatus.Watching, 6),
                    entry(anime(MediaType.Ova, 30, 2, 2015, listOf(action)), ListStatus.Dropped, 1, 3),
                    entry(anime(MediaType.Tv, 24, 13, 2021, listOf(comedy)), ListStatus.Planned, 0, 4),
                    entry(anime(MediaType.Ona, 15, 10, 2020, emptyList()), ListStatus.OnHold, 4, 6),
                ),
            )

        assertEquals(StatusCounts(watching = 1, completed = 2, onHold = 1, dropped = 1, planned = 1), statistics.statuses)
        assertEquals(6, statistics.titleCount)
        assertEquals(5, statistics.scored)
        assertEquals(1, statistics.unscored)
        // 12 × 24 + 1 × 120 + 6 × 24 + 1 × 30 + 0 × 24 + 4 × 15 = 642 minutes, a day being 1440.
        assertEquals(642.0 / 1440, statistics.daysWatched, 0.0)
        assertEquals(12 + 1 + 6 + 1 + 0 + 4, statistics.episodesWatched)
        // (8 + 10 + 3 + 4 + 6) / 5: the Planned entry's score is in, the one entry without a score is left out.
        assertEquals(6.2, statistics.meanScore!!, 0.0)
        assertEquals(mapOf(3 to 1, 4 to 1, 6 to 1, 8 to 1, 10 to 1), statistics.scores)
        // Supernatural (37) and Military (38) tie at one: by id, not by name.
        assertEquals(listOf(Counted(comedy, 3), Counted(action, 2), Counted(supernatural, 1), Counted(military, 1)), statistics.genres)
        // Movie, OVA and ONA tie at one, in the calculator's order of types.
        assertEquals(
            listOf(Counted(MediaType.Tv, 3), Counted(MediaType.Movie, 1), Counted(MediaType.Ova, 1), Counted(MediaType.Ona, 1)),
            statistics.mediaTypes,
        )
        assertEquals(mapOf(2015 to 1, 2019 to 1, 2020 to 3, 2021 to 1), statistics.years)
    }

    @Test
    fun `an empty list counts nothing and has no mean score`() {
        val statistics = statisticsOf(emptyList())

        assertEquals(none, statistics.statuses)
        assertEquals(0.0, statistics.daysWatched, 0.0)
        assertEquals(0, statistics.episodesWatched)
        assertEquals(0, statistics.titleCount)
        assertEquals(0, statistics.scored)
        assertEquals(0, statistics.unscored)
        assertNull(statistics.meanScore)
        assertEquals(emptyMap<Int, Int>(), statistics.scores)
        assertEquals(emptyList<Counted<Genre>>(), statistics.genres)
        assertEquals(emptyList<Counted<MediaType>>(), statistics.mediaTypes)
        assertEquals(emptyMap<Int, Int>(), statistics.years)
    }

    @Test
    fun `an entry without a score is in neither the mean nor the scores, and counts everywhere else`() {
        val scored = statisticsOf(listOf(entry(anime(), score = 7), entry(anime(), watched = 12)))
        assertEquals(7.0, scored.meanScore!!, 0.0)
        assertEquals(mapOf(7 to 1), scored.scores)
        assertEquals(2, scored.statuses.completed)
        assertEquals(12.0 * 24 / 1440, scored.daysWatched, 0.0)

        assertEquals(2, scored.titleCount)
        assertEquals(1, scored.scored)
        assertEquals(1, scored.unscored)

        val unscored = statisticsOf(listOf(entry(anime(), watched = 12)))
        assertNull(unscored.meanScore)
        assertEquals(emptyMap<Int, Int>(), unscored.scores)
    }

    @Test
    fun `a title without an episode length adds no time, and counts everywhere else`() {
        val statistics =
            statisticsOf(listOf(entry(anime(minutes = null), watched = 10, score = 9), entry(anime(minutes = 24), watched = 5)))

        assertEquals(5.0 * 24 / 1440, statistics.daysWatched, 0.0)
        assertEquals(15, statistics.episodesWatched)
        assertEquals(2, statistics.statuses.completed)
        assertEquals(mapOf(9 to 1), statistics.scores)
        assertEquals(listOf(Counted(MediaType.Tv, 2)), statistics.mediaTypes)
    }

    @Test
    fun `an episode length counts to the second`() {
        // MAL gives lengths in seconds: 3 × 1435 s = 4305 s.
        val statistics = statisticsOf(listOf(entry(anime(minutes = null, seconds = 1_435), watched = 3)))

        assertEquals(4_305.0 / 86_400, statistics.daysWatched, 0.0)
    }

    @Test
    fun `a title without a start date is in no year, and counts everywhere else`() {
        val statistics = statisticsOf(listOf(entry(anime(year = null), score = 5), entry(anime(year = 2004))))

        assertEquals(mapOf(2004 to 1), statistics.years)
        assertEquals(2, statistics.statuses.completed)
        assertEquals(mapOf(5 to 1), statistics.scores)
    }

    @Test
    fun `a title without a media type is in no type, ties follow the calculator's order, and an unknown type counts as its own`() {
        val known =
            listOf(
                MediaType.Tv,
                MediaType.TvSpecial,
                MediaType.Movie,
                MediaType.Ova,
                MediaType.Ona,
                MediaType.Special,
                MediaType.Music,
                MediaType.Commercial,
                MediaType.PromotionalVideo,
            )
        val unknown = listOf(MediaType.Unrecognized("cm_short"), MediaType.Unrecognized("tv_short"))
        val statistics =
            statisticsOf(
                listOf(entry(anime(type = null), score = 4)) + (known + unknown).reversed().map { entry(anime(type = it)) },
            )

        assertEquals((known + unknown).map { Counted(it, 1) }, statistics.mediaTypes)
        assertEquals(12, statistics.statuses.completed)
        assertEquals(mapOf(4 to 1), statistics.scores)
    }

    @Test
    fun `a title without genres is in no genre, a genre is counted once per title by its id, and ties go by id`() {
        val renamed = Genre(comedy.id, "Comedy (renamed)")
        val statistics =
            statisticsOf(
                listOf(
                    entry(anime(genres = null), score = 2),
                    entry(anime(genres = emptyList())),
                    entry(anime(genres = listOf(comedy, comedy, drama))),
                    entry(anime(genres = listOf(renamed))),
                    entry(anime(genres = listOf(military, supernatural))),
                ),
            )

        // Drama (8), Supernatural (37) and Military (38) tie at one: by name Military would come before Supernatural.
        assertEquals(
            listOf(Counted(comedy, 2), Counted(drama, 1), Counted(supernatural, 1), Counted(military, 1)),
            statistics.genres,
        )
        assertEquals(5, statistics.statuses.completed)
        assertEquals(mapOf(2 to 1), statistics.scores)
    }

    @Test
    fun `an entry of a status the app does not know is in no status count, and counts everywhere else`() {
        val statistics =
            statisticsOf(
                listOf(
                    entry(anime(MediaType.Movie, 100, 1, 1999, listOf(drama)), EntryStatus.Unrecognized("rewatching"), 1, 9),
                    entry(anime(MediaType.Tv, 24, 12, 2020, listOf(comedy)), ListStatus.Watching, 2, 5),
                ),
            )

        assertEquals(StatusCounts(watching = 1, completed = 0, onHold = 0, dropped = 0, planned = 0), statistics.statuses)
        assertEquals((100.0 + 2 * 24) / 1440, statistics.daysWatched, 0.0)
        assertEquals(3, statistics.episodesWatched)
        // Counted in the titles as in the scores, so the titles scored are never more than the titles.
        assertEquals(2, statistics.titleCount)
        assertEquals(2, statistics.scored)
        assertEquals(0, statistics.unscored)
        assertTrue(statistics.scored <= statistics.titleCount)
        assertEquals(7.0, statistics.meanScore!!, 0.0)
        assertEquals(mapOf(5 to 1, 9 to 1), statistics.scores)
        assertEquals(listOf(Counted(comedy, 1), Counted(drama, 1)), statistics.genres)
        assertEquals(listOf(Counted(MediaType.Tv, 1), Counted(MediaType.Movie, 1)), statistics.mediaTypes)
        assertEquals(mapOf(1999 to 1, 2020 to 1), statistics.years)
    }

    @Test
    fun `episodes are counted as the entry gives them, above the total too and below it when completed`() {
        val above = statisticsOf(listOf(entry(anime(minutes = 24, episodes = 12), ListStatus.Watching, 30)))
        assertEquals(30.0 * 24 / 1440, above.daysWatched, 0.0)
        assertEquals(30, above.episodesWatched)

        val unknownTotal = statisticsOf(listOf(entry(anime(minutes = 24, episodes = null), ListStatus.Watching, 1100)))
        assertEquals(1100.0 * 24 / 1440, unknownTotal.daysWatched, 0.0)

        val completedShort = statisticsOf(listOf(entry(anime(minutes = 24, episodes = 12), ListStatus.Completed, 3)))
        assertEquals(3.0 * 24 / 1440, completedShort.daysWatched, 0.0)
    }

    @Test
    fun `a list of several thousand entries is counted whole`() {
        val entries = (1..5_000).map { entry(anime(minutes = 1), ListStatus.Completed, watched = 1, score = it % 10 + 1) }
        val statistics = statisticsOf(entries)

        assertEquals(5_000, statistics.statuses.completed)
        assertEquals(5_000.0 / 1440, statistics.daysWatched, 0.0)
        assertEquals((1..10).associateWith { 500 }, statistics.scores)
        assertEquals(5.5, statistics.meanScore!!, 0.0)
    }
}
