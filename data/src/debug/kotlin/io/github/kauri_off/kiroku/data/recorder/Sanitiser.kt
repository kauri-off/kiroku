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
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

internal const val KEPT_ENTRIES = 10

// The longest paragraph, in characters, kept of a text that is somebody's prose.
internal const val FREE_TEXT_LIMIT = 60

internal val FreeTexts = setOf("synopsis", "background")

// The longest line that says whose a free text is.
internal const val SOURCE_LIMIT = 100

private val FreeTextSource = Regex("""\[Written by .*]|\(Source: .*\)""")

// The line that says whose a free text is; as the text's last paragraph it is kept whole.
internal fun isClosingSource(paragraph: String): Boolean =
    FreeTextSource.matches(paragraph) && paragraph.codePointCount(0, paragraph.length) <= SOURCE_LIMIT

// No made-up day is after this one, which is before every recording.
internal val LAST_MADE_UP_DAY: LocalDate = LocalDate.of(2026, 11, 1)

// Section 11.1: what is stored of an answer holds none of [secrets], no prose and nothing of a user's list but its shape.
internal class Sanitiser(
    secrets: Map<String, String>,
    // User names and what stands for each: replaced where a value is the name, and after `users/` in an address.
    names: Map<String, String> = emptyMap(),
) {
    private val standIns = secrets.filterKeys { it.isNotBlank() }.toList()
    private val users = names.filterKeys { it.isNotBlank() }.toList()
    private val inAddress =
        users.map { (name, standIn) ->
            Regex("(?<=users/)" + Regex.escape(name) + """(?=[/?#"\s]|$)""", RegexOption.IGNORE_CASE) to standIn
        }

    private class Found(
        val start: Int,
        val end: Int,
        val standIn: String,
    )

    // The answer as it is stored, or null when it is not JSON: such a body is not stored.
    fun json(body: String): String? = stored(body) { values(page(it)) }

    private fun stored(
        body: String,
        cleaned: (JsonElement) -> JsonElement,
    ): String? {
        val json =
            try {
                Json.parseToJsonElement(body)
            } catch (_: SerializationException) {
                null
            }
        val answer = json.takeIf { it is JsonObject || it is JsonArray } ?: return null
        return unnamed(Pretty.encodeToString(JsonElement.serializer(), cleaned(answer))) + "\n"
    }

    // A text without the secrets and without a user's name where an address names the user.
    fun plain(text: String): String = unnamed(text)

    // An answer as it was sent, without the secrets.
    fun asSent(body: String): String = withoutSecrets(body)

    fun privateAnswer(
        name: String,
        status: Int,
        body: String,
    ): Kept {
        val answer =
            try {
                Json.parseToJsonElement(body)
            } catch (_: SerializationException) {
                null
            }
        return when (answer) {
            is JsonObject, is JsonArray -> Kept("$name.$status.json", withoutPrivateSecrets(answer).toString())
            else -> Kept("$name.$status.txt", "$status\n")
        }
    }

    private class TextLayer(
        val text: String,
        private val offsets: IntArray? = null,
    ) {
        fun source(index: Int): Int = offsets?.get(index) ?: index

        fun decode(): TextLayer? {
            val escapes = PercentCharacter.findAll(text).iterator()
            if (!escapes.hasNext()) return null
            val decoded = StringBuilder()
            val positions = IntArray(text.length + 1)
            var end = 0

            fun copy(until: Int) {
                while (end < until) {
                    decoded.append(text[end++])
                    positions[decoded.length] = source(end)
                }
            }
            while (escapes.hasNext()) {
                val escape = escapes.next()
                copy(escape.range.first)
                val bytes =
                    ByteArray(escape.value.length / 3) { index ->
                        escape.value
                            .substring(index * 3 + 1, index * 3 + 3)
                            .toInt(16)
                            .toByte()
                    }
                val character = bytes.decodeToString()
                end = escape.range.last + 1
                for (unit in character) {
                    decoded.append(unit)
                    positions[decoded.length] = source(end)
                }
            }
            copy(text.length)
            return TextLayer(decoded.toString(), positions)
        }
    }

    private fun withoutPrivateSecrets(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    buildMap {
                        for ((name, value) in element) {
                            val clean = withoutSecrets(name, encoded = true)
                            var key = clean
                            var suffix = 1
                            while (clean != name && (key in element || key in this)) key = "$clean-${++suffix}"
                            put(key, withoutPrivateSecrets(value))
                        }
                    },
                )
            is JsonArray -> JsonArray(element.map(::withoutPrivateSecrets))
            is JsonPrimitive -> if (element.isString) JsonPrimitive(withoutSecrets(element.content, encoded = true)) else element
        }

    private fun unnamed(text: String): String =
        inAddress.fold(withoutSecrets(text)) { cleaned, (name, standIn) -> name.replace(cleaned) { standIn } }

    // Secrets that overlap or touch are replaced as one, by the stand-in of the one that starts them.
    private fun withoutSecrets(
        text: String,
        encoded: Boolean = false,
    ): String {
        val layers = if (encoded) generateSequence(TextLayer(text), TextLayer::decode) else sequenceOf(TextLayer(text))
        val found =
            layers.flatMap { layer ->
                standIns.asSequence().flatMap { (secret, standIn) ->
                    starts(layer.text, secret).map { Found(layer.source(it), layer.source(it + secret.length), standIn) }
                }
            }
        val cleaned = StringBuilder()
        var end = 0
        for ((index, range) in found.sortedWith(compareBy<Found> { it.start }.thenByDescending { it.end }).withIndex()) {
            if (index > 0 && range.start <= end) {
                end = maxOf(end, range.end)
            } else {
                cleaned.append(text, end, range.start).append(range.standIn)
                end = range.end
            }
        }
        return cleaned.append(text, end, text.length).toString()
    }

    private fun starts(
        text: String,
        secret: String,
    ): Sequence<Int> =
        generateSequence(text.indexOf(secret, ignoreCase = true)) { text.indexOf(secret, it + 1, ignoreCase = true) }.takeWhile { it >= 0 }

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
                val node = json["node"] as? JsonObject ?: json
                val title = (node["id"] as? JsonPrimitive)?.longOrNull ?: 0
                val aired = node["start_date"]?.takeIf { it.isText }?.let { firstDay(it.text) }
                JsonObject(
                    json.mapValues { (key, value) ->
                        when {
                            key in FreeTexts && value.isText -> JsonPrimitive(shortened(plain(value.text)))
                            key in LIST_VALUES && value is JsonObject -> madeUp(value, title, aired)
                            else -> values(value)
                        }
                    },
                )
            }
            is JsonPrimitive -> if (json.isString) JsonPrimitive(text(json.content)) else json
        }

    // A text of an answer: a user's name where it is the whole value, a moment in UTC.
    private fun text(real: String): String {
        val named = named(real)
        return when {
            named != real -> named
            MomentStart.containsMatchIn(real) -> utc(real) ?: real
            else -> real
        }
    }

    // A value that is a user's name, whatever its case, is the user's stand-in.
    private fun named(text: String): String = users.firstOrNull { (name, _) -> name.equals(text, ignoreCase = true) }?.second ?: text

    // The moment MAL wrote, without the offset it wrote it in and without a part of a second; null when it is none.
    private fun utc(moment: String): String? =
        try {
            OffsetDateTime.parse(moment).withOffsetSameInstant(ZoneOffset.UTC).format(Moment) + UTC
        } catch (_: DateTimeException) {
            null
        }

    // Every paragraph is cut at a word, and every line break stays; no secret is in [text], so none is cut in two.
    private fun shortened(text: String): String {
        val last = Paragraph.findAll(text).lastOrNull()?.range
        return Paragraph.replace(text) { if (it.range == last && isClosingSource(it.value)) it.value else cut(it.value) }
    }

    private fun cut(paragraph: String): String {
        if (paragraph.codePointCount(0, paragraph.length) <= FREE_TEXT_LIMIT) return paragraph
        val start = paragraph.substring(0, paragraph.offsetByCodePoints(0, FREE_TEXT_LIMIT - 1))
        return start.substringBeforeLast(' ').trimEnd() + "…"
    }

    // Status and count stay; the dates are made up in order: the title's start, start, finish, last change, the last day.
    private fun madeUp(
        real: JsonObject,
        title: Long,
        aired: LocalDate?,
    ): JsonObject {
        val first = aired?.takeIf { it.year >= FIRST_YEAR }?.coerceAtMost(LAST_MADE_UP_DAY) ?: WITHOUT_A_START
        val started = first.plusDays(Math.floorMod(title, ChronoUnit.DAYS.between(first, LAST_MADE_UP_DAY) + 1))
        val finished = started.plusDays(Math.floorMod(title, MOST_DAYS)).coerceAtMost(LAST_MADE_UP_DAY)
        val last = finished.takeIf { real[FINISH]?.isText == true } ?: started.takeIf { real[START]?.isText == true }
        val updated =
            last?.atStartOfDay()?.plusSeconds(Math.floorMod(title * 7919, SECONDS_OF_A_DAY))
                ?: LocalDateTime.of(2022, 1, 1, 0, 0).plusSeconds(Math.floorMod(title * 7919, SECONDS_OF_A_YEAR))
        return JsonObject(
            real.mapValues { (name, value) ->
                when {
                    name in KEPT_LIST_VALUES || value is JsonNull -> value
                    name == "score" ->
                        if ((value as? JsonPrimitive)?.longOrNull == 0L) value else JsonPrimitive(1 + Math.floorMod(title, 10L))
                    name == START && value.isText -> JsonPrimitive(date(value.text, started))
                    name == FINISH && value.isText -> JsonPrimitive(date(value.text, finished))
                    name == UPDATED && value.isText -> JsonPrimitive(updated.format(Moment) + UTC)
                    else -> blank(value)
                }
            },
        )
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

    // The first day a date of MAL's can mean, or null when it is no date.
    private fun firstDay(date: String): LocalDate? =
        try {
            LocalDate.parse(date + "-01-01".drop(maxOf(0, date.length - 4)))
        } catch (_: DateTimeParseException) {
            null
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
        const val CONTINUATION_BYTE = "(?:%[89ab][0-9a-f])"
        val PercentCharacter =
            Regex(
                "%[0-7][0-9a-f]|%[cd][0-9a-f]$CONTINUATION_BYTE|%e[0-9a-f]${CONTINUATION_BYTE}{2}|%f[0-4]${CONTINUATION_BYTE}{3}",
                RegexOption.IGNORE_CASE,
            )
        const val ENTRIES = "data"
        const val LINKS = "paging"
        const val START = "start_date"
        const val FINISH = "finish_date"
        const val UPDATED = "updated_at"
        const val UTC = "+00:00"
        const val SECONDS_OF_A_YEAR = 31_536_000L
        const val SECONDS_OF_A_DAY = 86_400L
        const val MOST_DAYS = 100L
        const val FIRST_YEAR = 1900
        val WITHOUT_A_START: LocalDate = LocalDate.of(2020, 1, 1)
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
