package io.github.kauri_off.kiroku.text

import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Source
import io.github.kauri_off.kiroku.data.db.ageRatings
import io.github.kauri_off.kiroku.data.db.mediaTypes
import io.github.kauri_off.kiroku.data.db.relationTypes
import io.github.kauri_off.kiroku.data.db.sources
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.titlesub.ratingName
import io.github.kauri_off.kiroku.titlesub.sourceName
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The screens' wrappers write MAL's values with the shared names, the unknown ones included.
@RunWith(RobolectricTestRunner::class)
class MalValueWrappersTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    fun `every wrapper gives the shared name`() {
        val types = mediaTypes.values.values + MediaType.Unrecognized("web_film")
        val relations = relationTypes.values.values + RelationType.Unrecognized("fan_fiction")
        val origins = sources.values.values + Source.Unrecognized("mixed_media_2")
        val ratings = ageRatings.values.values + AgeRating.Unrecognized("r18")
        val genres = genreNames.keys.map { Genre(it, "") } + Genre(999, "Slow Life")
        var shown: List<String> = emptyList()

        compose.setContent {
            shown = types.map { mediaTypeLabel(it) } + relations.map { relationName(it) } + origins.map { sourceName(it) } +
                ratings.map { ratingName(it) } + genres.map { genreName(it) }
        }
        compose.waitForIdle()

        val string: (Int) -> String = RuntimeEnvironment.getApplication()::getString
        val shared =
            types.map { it.named(string) } + relations.map { it.named(string) } + origins.map { it.named(string) } +
                ratings.map { it.named(string) } + genres.map { it.named(string) }
        assertEquals(shared, shown)
    }
}
