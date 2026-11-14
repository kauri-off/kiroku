package io.github.kauri_off.kiroku.data.recorder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Section 11.1: every recorded answer in the repository can be recorded again from a recorder's list.
class RecordedAnswersTest {
    private val listed = ClientIdRequests.flatMap { it.files }

    @Test
    fun `every recorded answer is named by a recorder's list`() {
        val stored = RecordedAnswers.files.map { it.name }

        assertTrue(stored.isNotEmpty())
        for (file in stored) {
            assertTrue(file, Regex("""[a-z0-9_]+\.\d{3}\.(json|txt)""").matches(file))
            assertTrue(file, file.substringBefore('.') in listed)
        }
        assertEquals(stored.size, stored.map { it.substringBefore('.') }.distinct().size)
    }

    @Test
    fun `a recorder's list names each file once`() {
        assertEquals(listed.distinct(), listed)
    }

    @Test
    fun `no recorded answer holds a paragraph of somebody's prose longer than the limit`() {
        val texts = RecordedAnswers.files.filter { it.extension == "json" }.flatMap { freeTexts(Json.parseToJsonElement(it.readText())) }

        assertTrue(texts.any { it.isNotEmpty() })
        for (paragraph in texts.flatMap { it.lines() }) {
            assertTrue(paragraph, paragraph.codePointCount(0, paragraph.length) <= FREE_TEXT_LIMIT || FreeTextSource.matches(paragraph))
        }
    }

    @Test
    fun `every recorded answer is as the sanitiser leaves it`() {
        val sanitiser = Sanitiser(emptyMap())

        for (file in RecordedAnswers.files.filter { it.extension == "json" }) {
            assertEquals(file.name, file.readText(), sanitiser.json(file.readText()))
        }
    }

    private fun freeTexts(json: JsonElement): List<String> =
        when (json) {
            is JsonObject ->
                json.flatMap { (key, value) ->
                    if (key in FreeTexts &&
                        value is JsonPrimitive
                    ) {
                        listOf(value.content)
                    } else {
                        freeTexts(value)
                    }
                }
            is JsonArray -> json.flatMap(::freeTexts)
            else -> emptyList()
        }
}
