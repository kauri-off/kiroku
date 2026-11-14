package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.Year
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
    fun `MAL's media type unknown is no media type`() {
        val (_, node, title) = mapped.single { (_, node) -> node.text("id") == "63143" }
        assertEquals("unknown", node.text("media_type"))
        assertNull(title.mediaType)
    }

    @Test
    fun `a code the app does not know is kept as MAL's code`() {
        val node = recordedNodes.first { (_, node) -> "media_type" in node }.second
        val title = node.edited(listOf("media_type"), JsonPrimitive("web_film")).toNode().toAnime()
        assertEquals(MediaType.Unrecognized("web_film"), title.mediaType)
    }

    // The store's codes are MAL's: every code the recordings hold is a value the app knows.
    @Test
    fun `every code in the recorded answers reads as a value the app knows`() {
        for ((answer, _, title) in mapped) assertFalse(answer, title.mediaType is MediaType.Unrecognized)
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

    @Test
    fun `a start date of a year or a month alone keeps its precision`() {
        val years = mapped.filter { (_, node) -> node.text("start_date")?.length == 4 }
        assertTrue(years.isNotEmpty())
        for ((_, node, title) in years) assertEquals(PartialDate.OnlyYear(Year.parse(node.text("start_date"))), title.startDate)
        val months = mapped.filter { (_, node) -> node.text("start_date")?.length == 7 }
        assertTrue(months.isNotEmpty())
        for ((_, node, title) in months) assertEquals(PartialDate.Month(YearMonth.parse(node.text("start_date"))), title.startDate)
    }

    @Test
    fun `a missing mean or rank is absent, and one that is there is kept`() {
        val noMean = mapped.filter { (_, node) -> "mean" !in node }
        val noRank = mapped.filter { (_, node) -> "rank" !in node }
        assertTrue(noMean.size > 10 && noRank.size > 10)
        for ((_, node, title) in mapped) {
            assertEquals(node.text("mean")?.toDouble(), title.meanScore)
            assertEquals(node.text("rank")?.toInt(), title.rank)
        }
    }

    // No recording holds these shapes: each is a recorded page with one value removed or altered here.
    @Test
    fun `a picture without its medium size is no picture, and the rest of the title is read`() {
        val whole = recordedTitle("title_airing")
        val edited = recordedJson("title_airing").edited(listOf("main_picture", "medium")).toTitle()
        assertEquals(whole.copy(anime = whole.anime.copy(posterUrl = null, largePosterUrl = null)), edited)
    }

    @Test
    fun `statistics missing a count are no statistics, and the rest of the title is read`() {
        val whole = recordedTitle("title_airing")
        val edited = recordedJson("title_airing").edited(listOf("statistics", "status", "dropped")).toTitle()
        assertEquals(whole.copy(details = whole.details.copy(statistics = null)), edited)
    }

    @Test
    fun `a broadcast time that does not parse is no time, and the rest of the title is read`() {
        val whole = recordedTitle("title_airing")
        val edited = recordedJson("title_airing").edited(listOf("broadcast", "start_time"), JsonPrimitive("24:00")).toTitle()
        assertEquals(whole.copy(anime = whole.anime.copy(broadcast = Broadcast(DayOfWeek.FRIDAY, null))), edited)
    }

    // Nothing fails because MAL sends a value or a shape the app does not expect: each makes its own value absent.
    private val airing by lazy { recordedTitle("title_airing") }

    private fun airingEdited(
        vararg path: String,
        value: JsonElement? = null,
    ): MalTitle = recordedJson("title_airing").edited(path.toList(), value).toTitle()

    private fun MalTitle.withAnime(change: Anime.() -> Anime) = copy(anime = anime.change())

    private fun MalTitle.withDetails(change: AnimeDetails.() -> AnimeDetails) = copy(details = details.change())

    @Test
    fun `an airing status the app does not know is kept as MAL's code, in a title and in a related one`() {
        assertEquals(
            airing.withAnime { copy(airingStatus = AiringStatus.Unrecognized("on_hiatus")) },
            airingEdited("status", value = JsonPrimitive("on_hiatus")),
        )
        val related = airing.details.related
        val first = related[0].copy(anime = related[0].anime.copy(airingStatus = AiringStatus.Unrecognized("on_hiatus")))
        assertEquals(
            airing.withDetails { copy(related = listOf(first) + related.drop(1)) },
            airingEdited("related_anime", "0", "node", "status", value = JsonPrimitive("on_hiatus")),
        )
    }

    @Test
    fun `a season the app does not know, or one without its year, is no season`() {
        val none = airing.withDetails { copy(season = null) }
        assertEquals(none, airingEdited("start_season", "season", value = JsonPrimitive("monsoon")))
        assertEquals(none, airingEdited("start_season", "year"))
    }

    @Test
    fun `a related title or a recommendation that cannot be mapped is left out, and the page shows`() {
        val related = airing.details.related
        assertEquals(airing.withDetails { copy(related = related.drop(1)) }, airingEdited("related_anime", "0", "node", "id"))
        assertEquals(airing.withDetails { copy(related = related - related[1]) }, airingEdited("related_anime", "1", "relation_type"))
        val finished = recordedTitle("title_finished")
        assertEquals(10, finished.details.recommendations.size)
        assertEquals(
            finished.withDetails { copy(recommendations = recommendations.drop(1)) },
            recordedJson("title_finished").edited(listOf("recommendations", "0", "node", "id")).toTitle(),
        )
    }

    // Only a node without a usable id cannot be mapped, and one without any title: the owner's rule for every node.
    @Test
    fun `a title without a usable id cannot be mapped`() {
        for (id in listOf(null, JsonNull, JsonPrimitive(0), JsonPrimitive(-5), JsonPrimitive("x"))) {
            assertThrows(id.toString(), IllegalArgumentException::class.java) { airingEdited("id", value = id) }
        }
    }

    @Test
    fun `a title without its status has the unknown one, which has no name`() {
        val none = airing.withAnime { copy(airingStatus = AiringStatus.Unrecognized("")) }
        assertEquals(none, airingEdited("status"))
        assertEquals(none, airingEdited("status", value = JsonNull))
        assertEquals(none, airingEdited("status", value = JsonPrimitive(" ")))
        val related = airing.details.related
        val first = related[0].copy(anime = related[0].anime.copy(airingStatus = AiringStatus.Unrecognized("")))
        assertEquals(
            airing.withDetails { copy(related = listOf(first) + related.drop(1)) },
            airingEdited("related_anime", "0", "node", "status"),
        )
    }

    @Test
    fun `a title without MAL's main title takes the English one, then the Japanese one, and with neither is left out`() {
        val titles = airing.anime.titles
        assertEquals(
            airing.withAnime { copy(titles = titles.copy(romaji = titles.english!!, english = null)) },
            airingEdited("title", value = JsonPrimitive("")),
        )
        assertEquals(airing.withAnime { copy(titles = titles.copy(romaji = titles.english!!, english = null)) }, airingEdited("title"))

        val related = airing.details.related
        val noEnglish = related.indexOfFirst { it.anime.id.value == 65119L }
        val japanese = related[noEnglish].anime.titles
        assertNull(japanese.english)
        val fallenTitles = japanese.copy(romaji = japanese.japanese!!, japanese = null)
        val fallen = related[noEnglish].copy(anime = related[noEnglish].anime.copy(titles = fallenTitles))
        assertEquals(
            airing.withDetails { copy(related = related.toMutableList().also { it[noEnglish] = fallen }) },
            airingEdited("related_anime", noEnglish.toString(), "node", "title"),
        )

        val page = recordedJson("title_unknown_episode_count")
        val whole = page.toTitle()
        val untitled = whole.details.related.indexOfFirst { it.anime.id.value == 57413L }
        assertEquals(
            whole.withDetails { copy(related = whole.details.related.filterIndexed { i, _ -> i != untitled }) },
            page.edited(listOf("related_anime", untitled.toString(), "node", "title")).toTitle(),
        )
    }

    @Test
    fun `a null where MAL leaves a field out is that field left out`() {
        assertEquals(airing.withAnime { copy(averageEpisodeDuration = null) }, airingEdited("average_episode_duration", value = JsonNull))
        val related = airing.details.related
        val first = related[0].copy(anime = related[0].anime.copy(averageEpisodeDuration = null))
        assertEquals(
            airing.withDetails { copy(related = listOf(first) + related.drop(1)) },
            airingEdited("related_anime", "0", "node", "average_episode_duration", value = JsonNull),
        )
        assertEquals(
            airing.withAnime { copy(titles = titles.copy(synonyms = emptyList())) },
            airingEdited("alternative_titles", "synonyms", value = JsonNull),
        )
        assertEquals(airing.withDetails { copy(studios = emptyList()) }, airingEdited("studios", value = JsonNull))
        assertEquals(airing.withDetails { copy(related = emptyList()) }, airingEdited("related_anime", value = JsonNull))
        assertEquals(airing.withDetails { copy(recommendations = emptyList()) }, airingEdited("recommendations", value = JsonNull))
        assertEquals(airing.withDetails { copy(statistics = null) }, airingEdited("statistics", "status", value = JsonNull))
    }

    @Test
    fun `a broadcast without its day is no broadcast`() {
        val none = airing.withAnime { copy(broadcast = null) }
        assertEquals(none, airingEdited("broadcast", "day_of_the_week"))
        assertEquals(none, airingEdited("broadcast", "day_of_the_week", value = JsonNull))
    }

    @Test
    fun `a date that does not parse is no date`() {
        assertEquals(airing.withAnime { copy(startDate = null) }, airingEdited("start_date", value = JsonPrimitive("2026-13-02")))
        val finished = recordedTitle("title_finished")
        assertEquals(
            finished.withDetails { copy(endDate = null) },
            recordedJson("title_finished").edited(listOf("end_date"), JsonPrimitive("spring")).toTitle(),
        )
    }

    @Test
    fun `a genre or a studio without its id or name, or with a blank name, is left out`() {
        val noFirst = airing.withAnime { copy(genres = genres!!.drop(1)) }
        assertEquals(noFirst, airingEdited("genres", "0", "name"))
        assertEquals(noFirst, airingEdited("genres", "0", "name", value = JsonPrimitive(" ")))
        assertEquals(noFirst, airingEdited("genres", "0", "id"))
        assertEquals(airing.withDetails { copy(studios = emptyList()) }, airingEdited("studios", "0", "name"))
    }

    @Test
    fun `an episode count, a mean or a rank that is not a number, or out of range, is absent`() {
        assertEquals(airing.withAnime { copy(malEpisodes = null) }, airingEdited("num_episodes", value = JsonPrimitive("twelve")))
        assertEquals(airing.withAnime { copy(malEpisodes = null) }, airingEdited("num_episodes", value = JsonPrimitive(-1)))
        assertEquals(airing.withAnime { copy(meanScore = null) }, airingEdited("mean", value = JsonPrimitive(10.5)))
        assertEquals(airing.withAnime { copy(meanScore = null) }, airingEdited("mean", value = JsonPrimitive("high")))
        assertEquals(airing.withAnime { copy(rank = null) }, airingEdited("rank", value = JsonPrimitive(0)))
        assertEquals(airing.withAnime { copy(rank = null) }, airingEdited("rank", value = JsonNull))
        assertEquals(
            airing.withAnime { copy(averageEpisodeDuration = null) },
            airingEdited("average_episode_duration", value = JsonPrimitive(1440.5)),
        )
    }

    @Test
    fun `a number MAL gives in quotes is read as the number, in every numeric field`() {
        val quoted = listOf("id", "num_episodes", "mean", "rank", "average_episode_duration")
        val json = recordedJson("title_airing")
        for (key in quoted) {
            val text = json.getValue(key).jsonPrimitive.content
            assertEquals(key, airing, airingEdited(key, value = JsonPrimitive(text)))
        }
        assertEquals(airing, airingEdited("start_season", "year", value = JsonPrimitive("2026")))
    }

    // The list keeps every entry (section 2.2): a node it cannot map, one without a usable id, fails the download.
    @Test
    fun `a list entry's title without a usable id is an error, never left out, and one without a status is kept`() {
        val entry = recordedJson("list_public_1").getValue("data").jsonArray[0]
        val node = entry.jsonObject.getValue("node").jsonObject
        assertThrows(IllegalArgumentException::class.java) { node.edited(listOf("id")).toNode().toAnime() }
        assertEquals(
            node.toNode().toAnime().copy(airingStatus = AiringStatus.Unrecognized("")),
            node.edited(listOf("status")).toNode().toAnime(),
        )
    }
}
