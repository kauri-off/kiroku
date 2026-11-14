package io.github.kauri_off.kiroku.data.recorder

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal const val KEPT_ENTRIES = 10

// The longest paragraph, in characters, kept of a text that is somebody's prose.
internal const val FREE_TEXT_LIMIT = 60

internal val FreeTexts = setOf("synopsis", "background")

// The line that says whose a free text is; as a text's last paragraph it is kept whole.
internal val FreeTextSource = Regex("""\[Written by .*]|\(Source: .*\)""")

// Section 11.1: what is stored of an answer holds none of [secrets], no prose and nothing of a user's list but its shape.
internal class Sanitiser(
    secrets: Map<String, String>,
) {
    private val standIns = secrets.filterKeys { it.isNotBlank() }.mapKeys { it.key.lowercase() }

    // One pass with the longest first, so a secret that holds another is replaced whole.
    private val secret =
        standIns.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { Regex(it, RegexOption.IGNORE_CASE) }

    // The answer as it is stored, or null when it is not JSON: such a body is not stored.
    fun json(body: String): String? {
        val json =
            try {
                Json.parseToJsonElement(body)
            } catch (_: SerializationException) {
                null
            }
        val answer = json.takeIf { it is JsonObject || it is JsonArray } ?: return null
        return plain(Pretty.encodeToString(JsonElement.serializer(), values(page(answer)))) + "\n"
    }

    fun plain(text: String): String = secret?.replace(text) { standIns[it.value.lowercase()] ?: "removed" } ?: text

    // A page of a list keeps its first entries, and its links are those of a page of so many.
    private fun page(json: JsonElement): JsonElement {
        val answer = json as? JsonObject ?: return json
        val entries = answer[ENTRIES] as? JsonArray ?: return json
        val links =
            (answer[LINKS] as? JsonObject)?.mapValues { (_, link) ->
                if (link.isText) JsonPrimitive(shortPage(link.text)) else link
            }
        return JsonObject(
            answer + (ENTRIES to JsonArray(entries.take(KEPT_ENTRIES))) + listOfNotNull(links?.let { LINKS to JsonObject(it) }),
        )
    }

    private fun shortPage(link: String): String {
        val size =
            PageSize
                .find(link)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull() ?: return link
        if (size <= KEPT_ENTRIES) return link
        return link
            .replace(PageSize) { "limit=$KEPT_ENTRIES" }
            .replace(PageStart) { "offset=${(it.groupValues[1].toLongOrNull() ?: 0) / size * KEPT_ENTRIES}" }
    }

    private fun values(json: JsonElement): JsonElement =
        when (json) {
            is JsonArray -> JsonArray(json.map(::values))
            is JsonObject -> {
                val title = ((json["node"] as? JsonObject ?: json)["id"] as? JsonPrimitive)?.longOrNull ?: 0
                JsonObject(
                    json.mapValues { (key, value) ->
                        when {
                            key in FreeTexts && value.isText -> JsonPrimitive(shortened(plain(value.text)))
                            key in LIST_VALUES && value is JsonObject ->
                                JsonObject(value.mapValues { (name, its) -> madeUp(name, its, title) })
                            else -> values(value)
                        }
                    },
                )
            }
            else -> json
        }

    // Every paragraph is cut at a word, and every line break stays; no secret is in [text], so none is cut in two.
    private fun shortened(text: String): String {
        val last = Paragraph.findAll(text).lastOrNull()?.range
        return Paragraph.replace(text) { if (it.range == last && FreeTextSource.matches(it.value)) it.value else cut(it.value) }
    }

    private fun cut(paragraph: String): String {
        if (paragraph.codePointCount(0, paragraph.length) <= FREE_TEXT_LIMIT) return paragraph
        val start = paragraph.substring(0, paragraph.offsetByCodePoints(0, FREE_TEXT_LIMIT - 1))
        return start.substringBeforeLast(' ').trimEnd() + "…"
    }

    // Of a user's values on a title the status and the count stay; the others are made up from the title's id, in MAL's shape.
    private fun madeUp(
        name: String,
        value: JsonElement,
        title: Long,
    ): JsonElement {
        val started = LocalDate.of(2020, 1, 1).plusDays(title % 1000)
        return when {
            name in KEPT_LIST_VALUES || value is JsonNull -> value
            name == "score" -> if ((value as? JsonPrimitive)?.longOrNull == 0L) value else JsonPrimitive(1 + title % 10)
            name == "start_date" && value.isText -> JsonPrimitive(date(value.text, started))
            name == "finish_date" && value.isText -> JsonPrimitive(date(value.text, started.plusDays(title % 100)))
            name == "updated_at" && value.isText -> JsonPrimitive(moment(value.text, title))
            else -> blank(value)
        }
    }

    // A date as complete as MAL's was.
    private fun date(
        real: String,
        made: LocalDate,
    ): String =
        when (real.length) {
            0 -> real
            in PARTIAL_DATES -> "$made".take(real.length)
            else -> "$made"
        }

    private fun moment(
        real: String,
        title: Long,
    ): String {
        val made = LocalDateTime.of(2022, 1, 1, 0, 0).plusSeconds(title * 7919 % SECONDS_OF_A_YEAR)
        return made.format(Moment) + (MomentStart.find(real)?.let { real.substring(it.value.length) } ?: "+00:00")
    }

    private fun blank(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> JsonObject(emptyMap())
            is JsonArray -> JsonArray(emptyList())
            is JsonPrimitive ->
                when {
                    value.isString -> JsonPrimitive("")
                    value.booleanOrNull != null -> JsonPrimitive(false)
                    else -> JsonPrimitive(0)
                }
        }

    private val JsonElement.isText get() = this is JsonPrimitive && isString

    private val JsonElement.text get() = (this as JsonPrimitive).content

    private companion object {
        const val ENTRIES = "data"
        const val LINKS = "paging"
        const val SECONDS_OF_A_YEAR = 31_536_000L
        val LIST_VALUES = setOf("list_status", "my_list_status")
        val KEPT_LIST_VALUES = setOf("status", "num_episodes_watched")
        val PARTIAL_DATES = setOf(4, 7)
        val PageSize = Regex("""\blimit=(\d+)""")
        val PageStart = Regex("""\boffset=(\d+)""")
        val Paragraph = Regex("""[^\r\n]+""")
        val Moment: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val MomentStart = Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d""")
        val Pretty = Json { prettyPrint = true }
    }
}
