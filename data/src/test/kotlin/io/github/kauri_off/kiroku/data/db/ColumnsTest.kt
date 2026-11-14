package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Broadcast
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Season
import io.github.kauri_off.kiroku.core.model.Source
import io.github.kauri_off.kiroku.core.model.Titles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Year
import java.time.YearMonth

class ColumnsTest {
    // A title with a value in every field, and the same title as an answer that brings none it may leave out.
    private val whole =
        Anime(
            id = AnimeId(1),
            titles = Titles("Romaji", "English", "日本語", listOf("One", "Two, \"quoted\"")),
            posterUrl = "https://example.invalid/poster.jpg",
            mediaType = MediaType.Unrecognized("web_film"),
            airingStatus = AiringStatus.Airing,
            malEpisodes = 24,
            startDate = PartialDate.Month(YearMonth.of(2025, 10)),
            broadcast = Broadcast(DayOfWeek.SUNDAY, LocalTime.of(23, 30)),
            meanScore = 8.37,
            rank = 112,
            genres = listOf(Genre(1, "Action"), Genre(8, "Drama")),
            averageEpisodeDuration = Duration.ofSeconds(1437, 5),
        )
    private val bare =
        Anime(
            id = AnimeId(1),
            titles = Titles("Other romaji"),
            posterUrl = null,
            mediaType = null,
            airingStatus = AiringStatus.Finished,
            malEpisodes = null,
            startDate = null,
            broadcast = null,
            meanScore = null,
            rank = null,
            genres = null,
            averageEpisodeDuration = null,
        )

    @Test
    fun `an instant comes back to the nanosecond, before the epoch too, and one a column cannot hold is refused`() {
        val instants =
            listOf(
                Instant.EPOCH,
                Instant.parse("2026-01-11T21:40:00.123456789Z"),
                Instant.parse("1969-12-31T23:59:59.999999999Z"),
                Instant.parse("1900-01-01T00:00:00.000000001Z"),
                Instant.parse("2200-06-01T12:00:00Z"),
            )
        for (instant in instants) assertEquals(instant, instantOf(instant.toColumn()))
        assertEquals(instants.sorted(), instants.sortedBy { it.toColumn() })
        assertThrows(ArithmeticException::class.java) { Instant.MAX.toColumn() }
        assertThrows(ArithmeticException::class.java) { Instant.MIN.toColumn() }
    }

    @Test
    fun `a date keeps its precision`() {
        val dates =
            listOf(
                PartialDate.OnlyYear(Year.of(2024)),
                PartialDate.Month(YearMonth.of(2024, 5)),
                PartialDate.Day(LocalDate.of(2024, 5, 3)),
                PartialDate.Day(LocalDate.of(1963, 1, 1)),
            )
        assertEquals(listOf("2024", "2024-05", "2024-05-03", "1963-01-01"), dates.map { it.toColumn() })
        for (date in dates) assertEquals(date, partialDateOf(date.toColumn()))
    }

    // The codes are the schema's: a change here is a migration.
    @Test
    fun `every value has the code it is stored under`() {
        assertEquals(
            listOf("watching", "planned", "completed", "on_hold", "dropped"),
            ListStatus.entries.map(listStatuses::toColumn),
        )
        assertEquals(listOf("not_yet_aired", "airing", "finished"), AiringStatus.entries.map(airingStatuses::toColumn))
        assertEquals(listOf("winter", "spring", "summer", "fall"), Season.entries.map(seasons::toColumn))
        assertEquals(
            "commercial movie music ona ova promotional_video special tv tv_special",
            objectsOf(MediaType::class.java).map(mediaTypes::toColumn).sorted().joinToString(" "),
        )
        assertEquals(
            "book card_game digital_manga four_koma_manga game light_novel manga mixed_media music novel original other " +
                "picture_book radio visual_novel web_manga web_novel",
            objectsOf(Source::class.java).map(sources::toColumn).sorted().joinToString(" "),
        )
        assertEquals(
            "all_ages children hentai mild_nudity teens_13 violence_17",
            objectsOf(AgeRating::class.java).map(ageRatings::toColumn).sorted().joinToString(" "),
        )
        assertEquals(
            "alternative_setting alternative_version character full_story other parent_story prequel sequel side_story " +
                "spin_off summary",
            objectsOf(RelationType::class.java).map(relationTypes::toColumn).sorted().joinToString(" "),
        )
    }

    @Test
    fun `every value comes back from its code, also one of a type's values added later`() {
        for (status in ListStatus.entries) assertEquals(status, listStatuses.of(listStatuses.toColumn(status)))
        for (status in AiringStatus.entries) assertEquals(status, airingStatuses.of(airingStatuses.toColumn(status)))
        for (season in Season.entries) assertEquals(season, seasons.of(seasons.toColumn(season)))
        for (type in objectsOf(MediaType::class.java)) assertEquals(type, mediaTypes.of(mediaTypes.toColumn(type)))
        for (source in objectsOf(Source::class.java)) assertEquals(source, sources.of(sources.toColumn(source)))
        for (rating in objectsOf(AgeRating::class.java)) assertEquals(rating, ageRatings.of(ageRatings.toColumn(rating)))
        for (relation in objectsOf(RelationType::class.java)) assertEquals(relation, relationTypes.of(relationTypes.toColumn(relation)))
    }

    @Test
    fun `a code the app does not recognise is stored and read as MAL gave it`() {
        for (code in listOf("web_film", "?", "", "pg_13 new")) {
            assertEquals(code, mediaTypes.toColumn(MediaType.Unrecognized(code)))
            assertEquals(MediaType.Unrecognized(code), mediaTypes.of(code))
            assertEquals(Source.Unrecognized(code), sources.of(sources.toColumn(Source.Unrecognized(code))))
            assertEquals(AgeRating.Unrecognized(code), ageRatings.of(ageRatings.toColumn(AgeRating.Unrecognized(code))))
            assertEquals(RelationType.Unrecognized(code), relationTypes.of(relationTypes.toColumn(RelationType.Unrecognized(code))))
        }
    }

    // What a version that did not know a code stored is what the version that knows it stores.
    @Test
    fun `a code stored before the app knew it is read as the value the app knows now`() {
        for (type in objectsOf(MediaType::class.java)) {
            assertEquals(type, mediaTypes.of(mediaTypes.toColumn(MediaType.Unrecognized(mediaTypes.toColumn(type)))))
        }
        for (source in objectsOf(Source::class.java)) {
            assertEquals(source, sources.of(sources.toColumn(Source.Unrecognized(sources.toColumn(source)))))
        }
        for (rating in objectsOf(AgeRating::class.java)) {
            assertEquals(rating, ageRatings.of(ageRatings.toColumn(AgeRating.Unrecognized(ageRatings.toColumn(rating)))))
        }
        for (relation in objectsOf(RelationType::class.java)) {
            val before = RelationType.Unrecognized(relationTypes.toColumn(relation))
            assertEquals(relation, relationTypes.of(relationTypes.toColumn(before)))
        }
    }

    @Test
    fun `a code of a type that has no unrecognised value is an error when no value is stored under it`() {
        assertThrows(IllegalStateException::class.java) { listStatuses.of("watched") }
        assertThrows(IllegalStateException::class.java) { airingStatuses.of("") }
        assertThrows(IllegalStateException::class.java) { seasons.of("autumn") }
    }

    @Test
    fun `a title comes back from its row as it was`() {
        val titles =
            Fixtures.reference.anime + whole + bare +
                whole.copy(broadcast = Broadcast(DayOfWeek.MONDAY, japanTime = null), genres = emptyList()) +
                whole.copy(broadcast = Broadcast(DayOfWeek.FRIDAY, LocalTime.of(1, 5, 30)), mediaType = MediaType.TvSpecial)
        assertTrue(titles.any { it.startDate is PartialDate.OnlyYear } && titles.any { it.startDate is PartialDate.Day })
        for (anime in titles) assertEquals(anime, anime.toRow().toAnime())
    }

    @Test
    fun `a stored value is never replaced by a missing one, and every other value is the newer one`() {
        val stored = whole.toRow()
        assertEquals(
            whole.copy(titles = whole.titles.copy(romaji = "Other romaji", synonyms = emptyList()), airingStatus = bare.airingStatus),
            stored.mergedWith(bare.toRow()).toAnime(),
        )

        val newer = FixtureAnime.exactToWatch.copy(id = whole.id)
        assertEquals(newer, stored.mergedWith(newer.toRow()).toAnime())
        assertEquals(stored, stored.mergedWith(stored))
        assertEquals(whole, bare.toRow().mergedWith(stored).toAnime())
    }

    @Test
    fun `each value a summary can leave out is kept by itself`() {
        val absences: List<(Anime) -> Anime> =
            listOf(
                { it.copy(titles = it.titles.copy(english = null)) },
                { it.copy(titles = it.titles.copy(japanese = null)) },
                { it.copy(posterUrl = null) },
                { it.copy(mediaType = null) },
                { it.copy(malEpisodes = null) },
                { it.copy(startDate = null) },
                { it.copy(broadcast = null) },
                { it.copy(meanScore = null) },
                { it.copy(rank = null) },
                { it.copy(genres = null) },
                { it.copy(averageEpisodeDuration = null) },
            )
        assertEquals(
            bare.toRow().copy(romaji = whole.titles.romaji, synonyms = whole.toRow().synonyms, airingStatus = "airing"),
            absences.fold(whole) { anime, without -> without(anime) }.toRow(),
        )
        for (without in absences) assertEquals(whole, whole.toRow().mergedWith(without(whole).toRow()).toAnime())
    }

    @Test
    fun `a broadcast is one value, so a newer day without a time does not keep the older time`() {
        val newer = whole.copy(broadcast = Broadcast(DayOfWeek.MONDAY, japanTime = null))
        assertEquals(newer, whole.toRow().mergedWith(newer.toRow()).toAnime())
    }

    // The objects among a sealed type's values, found without a list that could miss one.
    private fun <T : Any> objectsOf(type: Class<T>): List<T> =
        type.declaredClasses
            .mapNotNull { nested -> nested.fields.firstOrNull { it.name == "INSTANCE" }?.get(null) }
            .filter(type::isInstance)
            .map { checkNotNull(type.cast(it)) }
            .also { assertTrue(it.size > 3) }
}
