package io.github.kauri_off.kiroku.text

import android.content.res.Configuration
import android.content.res.Resources
import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.AnimeSeason
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Season
import io.github.kauri_off.kiroku.core.model.Source
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Locale

// ARCHITECTURE section 10: every value MAL sends that reads as interface text has a name in English and in Russian.
@RunWith(RobolectricTestRunner::class)
class MalValueNamesTest {
    private val english = resources("en")
    private val russian = resources("ru")

    @Test
    fun `every code in the recorded answers has a name in both languages`() {
        val codes = RecordedCodes.read()

        for ((kind, table) in codeTables) {
            val recorded = codes.of(kind)
            assertTrue(kind, recorded.isNotEmpty())
            for (code in recorded - NotAValue[kind].orEmpty()) {
                assertTrue("$kind $code is not a code the app knows", code in table)
                assertTranslated("$kind $code", table[code])
            }
        }
    }

    @Test
    fun `every genre in the recorded answers has a name in both languages and the table holds no other`() {
        val genres = RecordedCodes.read().genres

        assertEquals(genres.keys, genreNames.keys)
        for ((id, malName) in genres) {
            val name = Genre(id, malName).name()
            assertTranslated("genre $id", name)
            assertTrue("genre $id is not MAL's $malName", english.getString(checkNotNull(name)).equals(malName, ignoreCase = true))
        }
    }

    @Test
    fun `every value the app knows has a name in both languages`() {
        val known: List<Pair<Any, Int?>> =
            MediaTypes.map { it to it.name() } + RelationType.known.map { it to it.name() } + Sources.map { it to it.name() } +
                AgeRatings.map { it to it.name() } + AiringStatus.entries.map { it to it.name() } +
                ListStatus.entries.map { it to it.label() } + Season.entries.map { it to AnimeSeason(YEAR, it).name() }

        for ((value, name) in known) assertTranslated("$value", name)
    }

    @Test
    fun `a season is written with its year`() {
        val fall = AnimeSeason(YEAR, Season.Fall)
        assertEquals("Fall\u00A02024", fall.named { id, year -> english.getString(id, year) })
        assertEquals("Осень\u00A02024", fall.named { id, year -> russian.getString(id, year) })
    }

    @Test
    fun `an unknown value is its code as words and an unknown genre MAL's name, in both languages`() {
        for (resources in listOf(english, russian)) {
            val string: (Int) -> String = resources::getString
            assertEquals("Light novel", MediaType.Unrecognized("light_novel").named(string))
            assertEquals("Mixed media", Source.Unrecognized("mixed_media").named(string))
            assertEquals("R18", AgeRating.Unrecognized("r18").named(string))
            assertEquals("Fan fiction", RelationType.Unrecognized("fan_fiction").named(string))
            assertEquals("Slow Life", Genre(UNKNOWN_GENRE, "Slow Life").named(string))
        }
    }

    // A name in English and a different one in Russian, unless the term is written in Latin letters in both.
    private fun assertTranslated(
        what: String,
        name: Int?,
    ) {
        assertNotNull("$what is written without a name", name)
        val inEnglish = english.getString(checkNotNull(name), YEAR)
        val inRussian = russian.getString(name, YEAR)
        assertTrue(what, inEnglish.isNotBlank() && inRussian.isNotBlank())
        if (inEnglish !in SameInBoth) assertNotEquals(what, inEnglish, inRussian)
    }

    private fun resources(language: String): Resources {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return app.createConfigurationContext(configuration).resources
    }

    private companion object {
        const val YEAR = 2024
        const val UNKNOWN_GENRE = 999
        val SameInBoth = setOf("OVA", "ONA", "CGDCT")

        val MediaTypes =
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
        val Sources =
            listOf(
                Source.Original,
                Source.Manga,
                Source.FourKomaManga,
                Source.WebManga,
                Source.DigitalManga,
                Source.Novel,
                Source.LightNovel,
                Source.WebNovel,
                Source.VisualNovel,
                Source.Game,
                Source.CardGame,
                Source.Book,
                Source.PictureBook,
                Source.Radio,
                Source.Music,
                Source.MixedMedia,
                Source.Other,
            )
        val AgeRatings =
            listOf(
                AgeRating.AllAges,
                AgeRating.Children,
                AgeRating.Teens13,
                AgeRating.Violence17,
                AgeRating.MildNudity,
                AgeRating.Hentai,
            )

        // Codes that say MAL does not know the value, so the app shows none.
        val NotAValue = mapOf("media_type" to setOf("unknown"))

        // MAL's codes as the recorded answers spell them.
        val codeTables: Map<String, Map<String, Int?>> =
            mapOf(
                "media_type" to
                    listOf(
                        "tv",
                        "tv_special",
                        "movie",
                        "ova",
                        "ona",
                        "special",
                        "music",
                        "cm",
                        "pv",
                    ).zip(MediaTypes.map { it.name() }).toMap(),
                "status" to
                    mapOf(
                        "not_yet_aired" to AiringStatus.NotYetAired.name(),
                        "currently_airing" to AiringStatus.Airing.name(),
                        "finished_airing" to AiringStatus.Finished.name(),
                    ),
                "list_status" to
                    mapOf(
                        "watching" to ListStatus.Watching.label(),
                        "completed" to ListStatus.Completed.label(),
                        "on_hold" to ListStatus.OnHold.label(),
                        "dropped" to ListStatus.Dropped.label(),
                        "plan_to_watch" to ListStatus.Planned.label(),
                    ),
                "rating" to listOf("g", "pg", "pg_13", "r", "r+", "rx").zip(AgeRatings.map { it.name() }).toMap(),
                "source" to
                    listOf(
                        "original",
                        "manga",
                        "4_koma_manga",
                        "web_manga",
                        "digital_manga",
                        "novel",
                        "light_novel",
                        "web_novel",
                        "visual_novel",
                        "game",
                        "card_game",
                        "book",
                        "picture_book",
                        "radio",
                        "music",
                        "mixed_media",
                        "other",
                    ).zip(Sources.map { it.name() }).toMap(),
                "season" to listOf("winter", "spring", "summer", "fall").zip(Season.entries.map { AnimeSeason(YEAR, it).name() }).toMap(),
                "relation_type" to
                    listOf(
                        "prequel",
                        "sequel",
                        "parent_story",
                        "full_story",
                        "side_story",
                        "spin_off",
                        "alternative_setting",
                        "alternative_version",
                        "summary",
                        "other",
                        "character",
                    ).zip(RelationType.known.map { it.name() }).toMap(),
            )

        // The resource a value is named with, or null when it is written without one.
        fun resourceOf(naming: ((Int) -> String) -> String): Int? {
            var read: Int? = null
            naming { id -> "".also { read = id } }
            return read
        }

        fun MediaType.name(): Int? = resourceOf { named(it) }

        fun RelationType.name(): Int? = resourceOf { named(it) }

        fun Source.name(): Int? = resourceOf { named(it) }

        fun AgeRating.name(): Int? = resourceOf { named(it) }

        fun AiringStatus.name(): Int? = resourceOf { named(it) }

        fun AnimeSeason.name(): Int? = resourceOf { string -> named { id, _ -> string(id) } }

        fun Genre.name(): Int? = resourceOf { named(it) }
    }
}

// The codes of MAL's values in the recorded answers, by the key that holds them.
private class RecordedCodes(
    private val codes: Map<String, Set<String>>,
    val genres: Map<Int, String>,
) {
    fun of(kind: String): Set<String> = codes[kind].orEmpty()

    companion object {
        fun read(): RecordedCodes {
            val codes = mutableMapOf<String, MutableSet<String>>()
            val genres = mutableMapOf<Int, String>()

            fun walk(
                json: Any?,
                key: String?,
            ) {
                when (json) {
                    is JSONObject ->
                        json.keys().forEach { name ->
                            val value = json.get(name)
                            when {
                                name == "genres" && value is JSONArray ->
                                    (0 until value.length()).map(value::getJSONObject).forEach {
                                        genres[it.getInt("id")] =
                                            it.getString("name")
                                    }
                                key == "list_status" && name == "status" -> codes.getOrPut("list_status", ::mutableSetOf) += value as String
                                key == "start_season" && name == "season" -> codes.getOrPut("season", ::mutableSetOf) += value as String
                                name in Kinds && value is String -> codes.getOrPut(name, ::mutableSetOf) += value
                                else -> walk(value, name)
                            }
                        }
                    is JSONArray -> (0 until json.length()).forEach { walk(json.get(it), key) }
                }
            }

            val folder = File(checkNotNull(RecordedCodes::class.java.getResource("/recorded")).toURI())
            folder.listFiles { file -> file.extension == "json" }.orEmpty().forEach { walk(JSONTokener(it.readText()).nextValue(), null) }
            return RecordedCodes(codes, genres)
        }

        private val Kinds = setOf("media_type", "status", "rating", "source", "relation_type")
    }
}
