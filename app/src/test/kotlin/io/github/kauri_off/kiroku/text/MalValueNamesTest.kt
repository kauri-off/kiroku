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
import io.github.kauri_off.kiroku.data.anime.NO_MEDIA_TYPE
import io.github.kauri_off.kiroku.data.db.ageRatings
import io.github.kauri_off.kiroku.data.db.airingStatuses
import io.github.kauri_off.kiroku.data.db.listStatuses
import io.github.kauri_off.kiroku.data.db.mediaTypes
import io.github.kauri_off.kiroku.data.db.relationTypes
import io.github.kauri_off.kiroku.data.db.seasons
import io.github.kauri_off.kiroku.data.db.sources
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `every code the mapper knows has a name in both languages, and every recorded code is one of them`() {
        val recorded = RecordedCodes.read()

        for ((kind, table) in codeTables) {
            for ((code, name) in table) assertTranslated("$kind $code", name)
            val codes = recorded.of(kind) - if (kind == "media_type") setOf(NO_MEDIA_TYPE) else emptySet()
            assertTrue(kind, codes.isNotEmpty())
            for (code in codes) assertTrue("$kind $code is not a code the app knows", code in table)
        }
    }

    @Test
    fun `a value has its own name`() {
        for ((kind, code, inEnglish, inRussian) in Pinned) {
            val name = checkNotNull(codeTables.getValue(kind)[code])
            assertEquals("$kind $code", inEnglish, english.getString(name))
            assertEquals("$kind $code", inRussian, russian.getString(name))
        }
        assertEquals("Super power", Genre(31, "Super Power").named(english::getString))
        assertEquals("Суперсила", Genre(31, "Super Power").named(russian::getString))
        assertEquals("Тайна", Genre(7, "Mystery").named(russian::getString))
        assertEquals("Детектив", Genre(39, "Detective").named(russian::getString))
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
    fun `a season is written with its year`() {
        val inEnglish = listOf("Winter", "Spring", "Summer", "Fall")
        val inRussian = listOf("Зима", "Весна", "Лето", "Осень")
        for ((index, season) in Season.entries.withIndex()) {
            val named = AnimeSeason(YEAR, season)
            assertEquals("${inEnglish[index]}\u00A0$YEAR", named.named { id, year -> english.getString(id, year) })
            assertEquals("${inRussian[index]}\u00A0$YEAR", named.named { id, year -> russian.getString(id, year) })
        }
    }

    @Test
    fun `an unknown value is its code as words and an unknown genre MAL's name, in both languages`() {
        for (resources in listOf(english, russian)) {
            val string: (Int) -> String = resources::getString
            assertEquals("Light novel", MediaType.Unrecognized("light_novel").named(string))
            assertEquals("Mixed media", Source.Unrecognized("mixed_media").named(string))
            assertEquals("R18", AgeRating.Unrecognized("r18").named(string))
            assertEquals("Fan fiction", RelationType.Unrecognized("fan_fiction").named(string))
            assertEquals("On hiatus", AiringStatus.Unrecognized("on_hiatus").named(string))
            assertNull(AiringStatus.Unrecognized("").named(string))
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

    private data class Pin(
        val kind: String,
        val code: String,
        val inEnglish: String,
        val inRussian: String,
    )

    private companion object {
        const val YEAR = 2024
        const val UNKNOWN_GENRE = 999
        val SameInBoth = setOf("OVA", "ONA", "CGDCT")

        // MAL's codes of each kind as the mapper reads them, each with the resource its value is named with.
        val codeTables: Map<String, Map<String, Int?>> =
            mapOf(
                "media_type" to mediaTypes.values.mapValues { it.value.name() },
                "status" to airingStatuses.values.mapValues { it.value.name() },
                "list_status" to listStatuses.values.mapValues { (it.value as ListStatus).label() },
                "rating" to ageRatings.values.mapValues { it.value.name() },
                "source" to sources.values.mapValues { it.value.name() },
                "season" to seasons.values.mapValues { AnimeSeason(YEAR, it.value).name() },
                "relation_type" to relationTypes.values.mapValues { it.value.name() },
            )

        val Pinned =
            listOf(
                Pin("media_type", "tv", "TV", "ТВ"),
                Pin("media_type", "movie", "Movie", "Фильм"),
                Pin("status", "currently_airing", "Airing", "Выходит"),
                Pin("status", "finished_airing", "Finished", "Завершено"),
                Pin("list_status", "plan_to_watch", "Planned", "Запланировано"),
                Pin("list_status", "completed", "Completed", "Просмотрено"),
                Pin("rating", "pg_13", "PG-13 (teens\u00A013\u00A0or older)", "PG-13 (от\u00A013\u00A0лет)"),
                Pin("rating", "r+", "R+ (mild nudity)", "R+ (частичная обнажённость)"),
                Pin("source", "manga", "Manga", "Манга"),
                Pin("source", "light_novel", "Light novel", "Ранобэ"),
                Pin("relation_type", "side_story", "Side story", "Побочная история"),
                Pin("relation_type", "alternative_setting", "Alternative setting", "Альтернативная вселенная"),
            )

        // The resource a value is named with, or null when it is written without one.
        fun resourceOf(naming: ((Int) -> String) -> String?): Int? {
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
            folder
                .listFiles { file -> file.extension == "json" && !file.name.startsWith("manga_") }
                .orEmpty()
                .forEach { walk(JSONTokener(it.readText()).nextValue(), null) }
            return RecordedCodes(codes, genres)
        }

        private val Kinds = setOf("media_type", "status", "rating", "source", "relation_type")
    }
}
