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
    // A user's id: replaced in a text that holds it as a whole number, and as a value only where a key says it is a user's.
    userId: String? = null,
    // The title a recorder put on the list itself: its values there are the recorder's, and stay as MAL wrote them.
    private val ownTitle: Long? = null,
) {
    private val standIns = secrets.filterKeys { it.isNotBlank() }.toList()
    private val users = names.filterKeys { it.isNotBlank() }.toList()
    private val inAddress =
        users.map { (name, standIn) ->
            Regex("(?<=users/)" + Regex.escape(name) + """(?=[/?#"\s]|$)""", RegexOption.IGNORE_CASE) to standIn
        }
    private val user = userId?.takeIf { id -> id.isNotEmpty() && id.all(Char::isDigit) }
    private val asNumber =
        userId?.takeIf { id -> id.isNotEmpty() && id.all(Char::isDigit) }?.let { Regex("""(?<![0-9])(?<![0-9]\.)$it(?![0-9]|\.[0-9])""") }

    private class Found(
        val start: Int,
        val end: Int,
        val standIn: String,
    )

    // The answer as it is stored, or null when it is not JSON: such a body is not stored.
    fun json(body: String): String? = stored(body) { values(page(it)) }

    // A user's profile as it is stored: its shape, with every value made up.
    fun profile(body: String): String? = stored(body, MadeUp()::of)

    // A page of the user's own list: MAL's shape and links, and no entry's title or values. [first] counts the entries before it.
    fun ownList(
        body: String,
        first: Int,
    ): String? = stored(body) { ownEntries(page(it), first) }

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

    // A text without the secrets, without a user's name where an address names the user, and without the user's id.
    fun plain(text: String): String = unnamed(text).let { asNumber?.replace(it, "0") ?: it }

    private fun unnamed(text: String): String =
        inAddress.fold(withoutSecrets(text)) { cleaned, (name, standIn) -> name.replace(cleaned) { standIn } }

    // Secrets that overlap or touch are replaced as one, by the stand-in of the one that starts them.
    private fun withoutSecrets(text: String): String {
        val found =
            standIns
                .flatMap { (secret, standIn) -> starts(text, secret).map { Found(it, it + secret.length, standIn) } }
                .sortedWith(compareBy<Found> { it.start }.thenByDescending { it.end })
        val cleaned = StringBuilder()
        var end = 0
        for ((index, range) in found.withIndex()) {
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

    private fun values(
        json: JsonElement,
        // The key the value stands under, through lists.
        under: String? = null,
    ): JsonElement =
        when (json) {
            is JsonArray -> JsonArray(json.map { values(it, under) })
            is JsonObject -> {
                val node = json["node"] as? JsonObject ?: json
                val title = (node["id"] as? JsonPrimitive)?.longOrNull ?: 0
                val aired = node["start_date"]?.takeIf { it.isText }?.let { firstDay(it.text) }
                JsonObject(
                    json.mapValues { (key, value) ->
                        when {
                            key in FreeTexts && value.isText -> JsonPrimitive(shortened(plain(value.text)))
                            key in LIST_VALUES && value is JsonObject && title != ownTitle -> madeUp(value, title, aired)
                            // The recorder writes neither, so what stands there is the user's own.
                            key in OWNERS_TEXTS && ownTitle != null -> blank(value)
                            isUsersId(key, under, value) -> JsonPrimitive(0)
                            else -> values(value, key)
                        }
                    },
                )
            }
            is JsonPrimitive -> if (json.isString) JsonPrimitive(text(json.content)) else json
        }

    // A number is the user's id where its key says so, never because it equals it: a title's own numbers stay.
    private fun isUsersId(
        key: String,
        under: String?,
        value: JsonElement,
    ): Boolean = value is JsonPrimitive && value.content == user && (key == "user_id" || (key == "id" && under == "user"))

    // A text of an answer: a user's name where it is the whole value, a moment in UTC, the user's id inside other words.
    private fun text(real: String): String {
        val named = named(real)
        return when {
            named != real -> named
            MomentStart.containsMatchIn(real) -> utc(real) ?: real
            real.all(Char::isDigit) -> real
            else -> asNumber?.replace(real, "0") ?: real
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

    // Of a page of the user's own list every entry is made up: all its titles take the shape of the page's first one.
    private fun ownEntries(
        page: JsonElement,
        first: Int,
    ): JsonElement {
        val answer = page as? JsonObject ?: return page
        val entries = answer[ENTRIES] as? JsonArray ?: return page
        val shape = (entries.firstOrNull() as? JsonObject)?.get(NODE)
        val made =
            entries.mapIndexed { index, entry ->
                val position = first + index
                JsonObject(
                    (entry as? JsonObject).orEmpty().mapValues { (key, value) ->
                        when {
                            key == NODE -> madeUpTitle(shape ?: value, position)
                            key in LIST_VALUES && value is JsonObject -> ownValues(value, position)
                            else -> MadeUp().of(value)
                        }
                    },
                )
            }
        return JsonObject(answer.mapValues { (key, value) -> if (key == ENTRIES) JsonArray(made) else values(value, key) })
    }

    // A title no list holds: ids MAL does not have, and a list of one value wherever the shape has a list.
    private fun madeUpTitle(
        shape: JsonElement,
        position: Int,
    ): JsonElement {
        val title = MadeUp(short = true).of(shape) as? JsonObject ?: return JsonNull
        val own =
            mapOf(
                "id" to JsonPrimitive(OWN_FIRST_ID + position),
                "title" to JsonPrimitive("Made-up title ${position + 1}"),
                "num_episodes" to JsonPrimitive(OWN_EPISODES),
                "media_type" to JsonPrimitive("tv"),
                "status" to JsonPrimitive("finished_airing"),
            )
        return JsonObject(title.mapValues { (key, value) -> own[key] ?: value })
    }

    // The entry's place on the page decides its status, and the status its count, score and dates: nothing is the user's.
    private fun ownValues(
        real: JsonObject,
        position: Int,
    ): JsonObject {
        val status = OWN_STATUSES[position % OWN_STATUSES.size]
        val planned = status == "plan_to_watch"
        val started = OWN_FIRST_DAY.plusDays(position.toLong())
        val updated = started.plusDays(OWN_DAYS)
        val values =
            real.filterKeys { it != START && it != FINISH }.mapValues { (name, value) ->
                when {
                    value is JsonNull -> value
                    name == "status" -> JsonPrimitive(status)
                    name == "num_episodes_watched" ->
                        JsonPrimitive(
                            when (status) {
                                "completed" -> OWN_EPISODES
                                "plan_to_watch" -> 0
                                else -> 1 + position % (OWN_EPISODES - 1)
                            },
                        )
                    name == "score" -> JsonPrimitive(if (planned) 0 else 1 + position % 10)
                    name == UPDATED && value.isText -> JsonPrimitive(updated.atStartOfDay().format(Moment) + UTC)
                    else -> blank(value)
                }
            }
        val dates =
            listOfNotNull(
                (START to JsonPrimitive(if (status == "on_hold") "$started".take(MONTH) else "$started")).takeUnless { planned },
                (FINISH to JsonPrimitive("$updated")).takeIf { status == "completed" },
            )
        return JsonObject(values + dates)
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

    // Nothing of a profile is harmless: a value stays only when it is empty, and otherwise keeps its kind and its form.
    private inner class MadeUp(
        // A list keeps one value, so that its length says nothing.
        private val short: Boolean = false,
    ) {
        private var made = 0

        fun of(json: JsonElement): JsonElement =
            when (json) {
                is JsonObject -> JsonObject(json.mapValues { (_, value) -> of(value) })
                is JsonArray -> JsonArray((if (short) json.take(1) else json).map(::of))
                is JsonNull -> json
                is JsonPrimitive -> if (json.isString) text(json.content) else number(json)
            }

        private fun text(real: String): JsonPrimitive {
            val known = (users + standIns).firstOrNull { (secret, _) -> secret.equals(real, ignoreCase = true) }?.second
            return JsonPrimitive(
                when {
                    real.isEmpty() -> real
                    known != null -> known
                    MomentStart.containsMatchIn(real) -> MADE_UP_MOMENT
                    Date.matches(real) -> MADE_UP_MOMENT.take(real.length)
                    Address.containsMatchIn(real) -> "https://example.org/made-up-${++made}"
                    else -> "made-up-${++made}"
                },
            )
        }

        private fun number(real: JsonPrimitive): JsonPrimitive =
            when {
                real.booleanOrNull != null -> JsonPrimitive(false)
                real.longOrNull != null -> JsonPrimitive(++made)
                else -> JsonPrimitive(++made + HALF)
            }
    }

    private val JsonElement.isText get() = this is JsonPrimitive && isString

    private val JsonElement.text get() = (this as JsonPrimitive).content

    private companion object {
        const val ENTRIES = "data"
        const val LINKS = "paging"
        const val NODE = "node"
        val OWNERS_TEXTS = setOf("comments", "tags")
        const val START = "start_date"
        const val FINISH = "finish_date"
        const val UPDATED = "updated_at"
        const val UTC = "+00:00"
        const val MONTH = 7
        const val SECONDS_OF_A_YEAR = 31_536_000L
        const val SECONDS_OF_A_DAY = 86_400L
        const val MOST_DAYS = 100L
        const val FIRST_YEAR = 1900
        val WITHOUT_A_START: LocalDate = LocalDate.of(2020, 1, 1)
        const val OWN_FIRST_ID = 90_000_001L
        const val OWN_EPISODES = 24
        const val OWN_DAYS = 30L
        val OWN_FIRST_DAY: LocalDate = LocalDate.of(2021, 1, 1)
        val OWN_STATUSES = listOf("watching", "completed", "on_hold", "dropped", "plan_to_watch")
        val LIST_VALUES = setOf("list_status", "my_list_status")
        val KEPT_LIST_VALUES = setOf("status", "num_episodes_watched")
        val PARTIAL_DATES = setOf(4, 7)
        val PageSize = Regex("""\blimit=(\d+)""")
        val PageStart = Regex("""\boffset=(\d+)""")
        val Paragraph = Regex("""[^\r\n]+""")
        val Moment: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val MomentStart = Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d""")
        val Date = Regex("""\d{4}(-\d\d){0,2}""")
        val Address = Regex("""^https?://""")
        const val MADE_UP_MOMENT = "2020-01-01T00:00:00+00:00"
        const val HALF = 0.5
        val Pretty = Json { prettyPrint = true }
    }
}
