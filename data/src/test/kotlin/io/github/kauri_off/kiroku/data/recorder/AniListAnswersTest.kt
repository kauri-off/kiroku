package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.anilist.AniListSchedule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Section 11.1 and M3-01: the recorded answers of AniList are the recorder's, hold only what is asked, and have MAL's view.
class AniListAnswersTest {
    private val files: List<File> =
        File(checkNotNull(javaClass.getResource("/anilist/recorded")).toURI()).listFiles().orEmpty().sortedBy { it.name }

    private val answers = files.filter { it.name != LIMIT_NOTE }

    @Test
    fun `every file is named by the recorder's list, and every request of the list has its answer`() {
        val names = AniListRequests.map { it.name }

        assertTrue(files.any { it.name == LIMIT_NOTE })
        for (file in answers) {
            assertTrue(file.name, Regex("""[a-z0-9_]+\.200\.json""").matches(file.name))
            assertTrue(file.name, pageOf(file.name) in names)
        }
        assertEquals(names.toSet(), answers.map { pageOf(it.name) }.toSet())
    }

    @Test
    fun `no file holds a field the requests do not ask for, nor an address`() {
        val asked = answerTo(AniListSchedule.QUERY)

        for (file in answers) {
            assertEquals(file.name, emptyList<String>(), asked.unasked(Json.parseToJsonElement(file.readText())))
        }
        for (file in files) {
            assertTrue(file.name, "://" !in file.readText())
        }
    }

    @Test
    fun `every title of a case has its MAL page among MAL's recorded answers`() {
        val ofMal =
            RecordedAnswers.files
                .filter { it.extension == "json" }
                .mapNotNull { (Json.parseToJsonElement(it.readText()) as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull }
                .toSet()

        for (ask in AniListRequests.filter { it.withMalPages }) {
            for (id in ask.ids) assertTrue("${ask.name}: $id", id in ofMal)
            for (file in answers.filter { pageOf(it.name) == ask.name }) {
                for (id in idsIn(Json.parseToJsonElement(file.readText()))) assertTrue("${file.name}: $id", id in ofMal)
            }
        }
    }

    @Test
    fun `every answer holds only titles its request asked for`() {
        for (file in answers) {
            val ask = AniListRequests.single { it.name == pageOf(file.name) }
            assertTrue(file.name, ask.ids.containsAll(idsIn(Json.parseToJsonElement(file.readText()))))
        }
    }

    private fun idsIn(answer: JsonElement): List<Int> =
        answer.jsonObject["data"]!!
            .jsonObject["Page"]!!
            .jsonObject["media"]!!
            .jsonArray
            .map { it.jsonObject["idMal"]!!.jsonPrimitive.int }

    @Test
    fun `a selection is checked field by field`() {
        val asked = answerTo(AniListSchedule.QUERY)
        val planted =
            Json.parseToJsonElement(
                """{"data":{"Page":{"media":[{"idMal":1,"coverImage":{"large":"x"},"status":{}}]}},"errors":[]}""",
            )

        assertEquals(listOf("/data/Page/media/coverImage", "/data/Page/media/status", "/errors"), asked.unasked(planted))
    }
}
