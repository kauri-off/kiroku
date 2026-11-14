package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.AnimeSeason
import io.github.kauri_off.kiroku.core.model.Broadcast
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Season
import io.github.kauri_off.kiroku.core.model.Source
import io.github.kauri_off.kiroku.core.model.StatusCounts
import io.github.kauri_off.kiroku.core.model.TitleStatistics
import io.github.kauri_off.kiroku.data.network.MalFields
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

// The mappers on MAL's recorded answers only.
class MalAnimeTest {
    private val mapped by lazy { recordedNodes.map { (answer, node) -> Triple(answer, node, node.toNode().toAnime()) } }

    private fun JsonObject.text(vararg path: String): String? {
        var element: JsonObject = this
        for (key in path.dropLast(1)) element = element[key]?.jsonObject ?: return null
        return element[path.last()]?.jsonPrimitive?.content
    }

    @Test
    fun `a title's page becomes the domain model`() {
        val json = recordedJson("title_airing")
        val (anime, details) = recordedTitle("title_airing")

        assertEquals(AnimeId(61987), anime.id)
        assertEquals("Kusuriya no Hitorigoto 3rd Season", anime.titles.romaji)
        assertEquals("The Apothecary Diaries Season 3", anime.titles.english)
        assertEquals("薬屋のひとりごと 第3期", anime.titles.japanese)
        assertEquals(listOf("The Pharmacist's Monologue", "Drugstore Soliloquy"), anime.titles.synonyms)
        assertEquals(MediaType.Tv, anime.mediaType)
        assertEquals(AiringStatus.Airing, anime.airingStatus)
        assertEquals(12, anime.malEpisodes)
        assertEquals(PartialDate.Day(LocalDate.of(2026, 10, 2)), anime.startDate)
        assertEquals(Broadcast(DayOfWeek.FRIDAY, LocalTime.of(23, 0)), anime.broadcast)
        assertEquals(8.6, anime.meanScore)
        assertEquals(117, anime.rank)
        assertEquals(listOf(8, 13, 67, 7), anime.genres?.map { it.id })
        assertEquals("Drama", anime.genres?.first()?.malName)
        assertEquals(Duration.ofMinutes(24), anime.averageEpisodeDuration)

        assertEquals(anime.id, details.id)
        assertEquals(json.text("synopsis"), details.synopsis)
        assertNull(details.endDate)
        assertEquals(AnimeSeason(2026, Season.Fall), details.season)
        assertEquals(Source.LightNovel, details.source)
        assertEquals(listOf("OLM"), details.studios)
        assertEquals(AgeRating.Teens13, details.rating)
        assertEquals(
            listOf(RelationType.Prequel, RelationType.Sequel, RelationType.Other, RelationType.Other, RelationType.Other),
            details.related.map { it.relation },
        )
        assertEquals(listOf(58514L, 62841, 65097, 65119, 65189), details.related.map { it.anime.id.value })
        assertEquals(1, details.recommendations.size)
    }

    @Test
    fun `title statistics are read from the strings and the number MAL gives them as`() {
        assertEquals(
            TitleStatistics(StatusCounts(watching = 107931, completed = 51, onHold = 1395, dropped = 270, planned = 119570), 229217),
            recordedTitle("title_airing").details.statistics,
        )
    }

    @Test
    fun `an end date and a finished title are read`() {
        val (anime, details) = recordedTitle("title_finished")
        assertEquals(AiringStatus.Finished, anime.airingStatus)
        assertEquals(26, anime.malEpisodes)
        assertNotNull(details.endDate)
        assertEquals(recordedJson("title_finished").text("end_date"), (details.endDate as PartialDate.Day).date.toString())
    }

    @Test
    fun `an episode count and an episode length of 0 are absent`() {
        val (anime) = recordedTitle("title_not_yet_aired")
        assertNull(anime.malEpisodes)
        assertNull(anime.averageEpisodeDuration)
        assertEquals(PartialDate.Month(YearMonth.of(2027, 10)), anime.startDate)
        assertNull(recordedTitle("title_unknown_episode_count").anime.malEpisodes)

        val zeros = mapped.filter { (_, node) -> node.text("num_episodes") == "0" }
        assertTrue(zeros.size > 10)
        for ((_, _, title) in zeros) assertNull(title.malEpisodes)
        for ((_, node, title) in mapped.filter { (_, node) -> node.text("average_episode_duration") == "0" }) {
            assertNull(node.text("id"), title.averageEpisodeDuration)
        }
        for ((_, node, title) in mapped.filter { (_, node) -> node.text("num_episodes") != "0" }) {
            assertEquals(node.text("num_episodes")?.toInt(), title.malEpisodes)
        }
    }

    @Test
    fun `an empty English or Japanese title is absent, and one that is there is kept`() {
        for (language in listOf("en", "ja")) {
            val empty = mapped.filter { (_, node) -> node.text("alternative_titles", language) == "" }
            assertTrue(language, empty.isNotEmpty())
            for ((_, _, title) in empty) {
                assertNull(if (language == "en") title.titles.english else title.titles.japanese)
            }
            for ((_, node, title) in mapped - empty.toSet()) {
                assertEquals(
                    node.text("alternative_titles", language),
                    if (language ==
                        "en"
                    ) {
                        title.titles.english
                    } else {
                        title.titles.japanese
                    },
                )
            }
        }
        assertNull(
            recordedTitle("title_unknown_episode_count")
                .details.related
                .single { it.anime.id.value == 57413L }
                .anime.titles.japanese,
        )
    }

    @Test
    fun `genres are null when an answer has none`() {
        val related = recordedTitle("title_unknown_episode_count").details.related.single { it.anime.id.value == 57413L }
        assertNull(related.anime.genres)
        for ((_, node, title) in mapped) {
            if ("genres" in node) assertEquals(node["genres"]!!.jsonArray.size, title.genres?.size) else assertNull(title.genres)
        }
    }

    @Test
    fun `a code the app does not know is kept as MAL's code`() {
        val unknown = mapped.single { (_, node) -> node.text("id") == "63143" }.third
        assertEquals(MediaType.Unrecognized("unknown"), unknown.mediaType)
    }

    // The store's codes are MAL's: every code the recordings hold is a value the app knows, but the media type "unknown".
    @Test
    fun `every code in the recorded answers reads as a value the app knows`() {
        for ((answer, node, title) in mapped) {
            if (node.text("media_type") != "unknown") assertFalse(answer, title.mediaType is MediaType.Unrecognized)
        }
        for (name in titlePages) {
            val (_, details) = recordedTitle(name)
            assertFalse(name, details.source is Source.Unrecognized)
            assertFalse(name, details.rating is AgeRating.Unrecognized)
            assertFalse(name, details.related.any { it.relation is RelationType.Unrecognized })
        }
        assertEquals(
            setOf(AiringStatus.NotYetAired, AiringStatus.Airing, AiringStatus.Finished),
            mapped.map { it.third.airingStatus }.toSet(),
        )
        assertEquals(AgeRating.Hentai, recordedTitle("title_adult_nsfw_true").details.rating)
        assertEquals(AgeRating.Violence17, recordedTitle("title_finished").details.rating)
    }

    @Test
    fun `every related title of the airing title has a media type and a start year`() {
        val related = recordedTitle("title_airing").details.related
        assertEquals(5, related.size)
        for (title in related) {
            assertNotNull(title.anime.mediaType)
            assertNotNull(title.anime.startDate?.year)
        }
        assertEquals(PartialDate.Month(YearMonth.of(2027, 4)), related[1].anime.startDate)
        assertEquals(MediaType.Music, related[2].anime.mediaType)
    }

    @Test
    fun `a related title without a start date has none, and its list status is not asked of MAL`() {
        val related = recordedTitle("title_unknown_episode_count").details.related
        assertNull(related.first { it.anime.id.value == 64859L }.anime.startDate)
        assertFalse(MalFields.DETAILS.contains("list_status"))
    }

    @Test
    fun `both picture sizes MAL gives are kept`() {
        for ((answer, node, title) in mapped) {
            assertEquals(answer, node.text("main_picture", "medium"), title.posterUrl)
            assertEquals(answer, node.text("main_picture", "large"), title.largePosterUrl)
            assertNotNull(answer, title.largePosterUrl)
        }
    }

    // The adult switch leaves out adult recommendations and nothing else; no switch is the switch off.
    @Test
    fun `the adult switch changes a title's recommendations only`() {
        val on = recordedTitle("title_adult_nsfw_true")
        val off = recordedTitle("title_adult_nsfw_false")
        assertEquals(off, recordedTitle("title_adult_nsfw_unset"))
        assertEquals(on.anime, off.anime)
        assertEquals(on.details.copy(recommendations = off.details.recommendations), off.details)
        assertEquals(4, off.details.related.size)
        assertTrue(
            off.details.related.all { related ->
                related.anime.genres
                    .orEmpty()
                    .any { it.malName == "Hentai" }
            },
        )
        assertEquals(10, on.details.recommendations.size)
        assertEquals(1, off.details.recommendations.size)
    }

    @Test
    fun `an empty list of related titles, recommendations or synonyms is empty`() {
        val (anime, details) = recordedTitle("title_no_related")
        assertTrue(details.related.isEmpty())
        assertTrue(anime.titles.synonyms.isEmpty())
        assertTrue(recordedTitle("title_not_yet_aired").details.recommendations.isEmpty())
        assertEquals(recordedJson("title_no_related")["related_anime"]!!.jsonArray.size, 0)
        assertEquals(
            0,
            recordedJson("title_no_related")
                .getValue("alternative_titles")
                .jsonObject["synonyms"]!!
                .jsonArray.size,
        )
    }

    @Test
    fun `a film is one episode of its length, without a broadcast`() {
        val (anime) = recordedTitle("title_film")
        assertEquals(MediaType.Movie, anime.mediaType)
        assertEquals(1, anime.malEpisodes)
        assertEquals(
            Duration.ofSeconds(recordedJson("title_film")["average_episode_duration"]!!.jsonPrimitive.int.toLong()),
            anime.averageEpisodeDuration,
        )
        assertNull(anime.broadcast)
    }
}
