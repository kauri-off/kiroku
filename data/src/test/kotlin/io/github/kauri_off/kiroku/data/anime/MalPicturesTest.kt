package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.model.TitlePicture
import io.github.kauri_off.kiroku.data.network.titlePictures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MalPicturesTest {
    @Test
    fun `picture identity keeps different sizes paths hosts and query strings distinct`() {
        val recording = recordedJson("title_pictures")
        val cover = recording["main_picture"] as JsonObject
        val medium = (cover["medium"] as JsonPrimitive).content
        val large = (cover["large"] as JsonPrimitive).content
        val pictures = recording["pictures"] as JsonArray
        val coverAsJpg = pictures[2] as JsonObject
        val different =
            listOf(
                large.replace(".webp", ".jpg").replace("19644l", "19644l2"),
                medium.replace(".webp", ".jpg").replace("/4/", "/12/"),
                medium.replace(".webp", ".jpg").replace("cdn.myanimelist.net", "example.invalid"),
                medium.replace(".webp", ".jpg") + "?other",
                medium.removeSuffix(".webp"),
            )

        val onlyLarge = TitlePicture(null, large)
        assertEquals(
            listOf(TitlePicture((coverAsJpg["medium"] as JsonPrimitive).content, null)),
            JsonArray(listOf(JsonObject(mapOf("medium" to coverAsJpg.getValue("medium"))))).titlePictures(onlyLarge) {
                error("Unexpected repair")
            },
        )
        val input = JsonArray(listOf(cover, coverAsJpg) + different.map { JsonObject(mapOf("medium" to JsonPrimitive(it))) })
        assertEquals(
            different.map { TitlePicture(it, null) },
            input.titlePictures(TitlePicture(medium, large)) { error("Unexpected repair") },
        )
    }

    @Test
    fun `an unreadable entry or size leaves the usable parts of the recording available`() {
        val original = recordedJson("title_pictures")
        val pictures = original["pictures"] as JsonArray
        val first = pictures[0] as JsonObject
        val changed = JsonArray(listOf(JsonNull, JsonObject(first + ("large" to JsonPrimitive(false)))) + pictures.drop(1))
        val answer = JsonObject(original + ("pictures" to changed))
        val repairs = Repairs()
        val gallery = answer.toTitle(repairs).details.pictures
        assertEquals(
            original
                .toTitle(Repairs())
                .details.pictures
                .drop(1),
            gallery.drop(1),
        )
        assertEquals(TitlePicture((first["medium"] as JsonPrimitive).content, null), gallery.first())
        assertEquals(setOf("pictures", "status"), repairs.fields)

        for (empty in listOf(JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()))) {
            val edited = JsonObject(original + ("pictures" to empty)).toTitle(Repairs())
            assertEquals(emptyList<TitlePicture>(), edited.details.pictures)
            assertEquals(original.toTitle(Repairs()).anime, edited.anime)
        }
    }
}
