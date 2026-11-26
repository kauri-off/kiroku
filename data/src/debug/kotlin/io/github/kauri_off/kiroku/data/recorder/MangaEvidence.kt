package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.core.repository.readingStatus
import io.github.kauri_off.kiroku.data.manga.isStrictMangaObject
import io.github.kauri_off.kiroku.data.network.MalJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

private val StatusCodes = MangaListStatus.entries.map { it.readingStatus.code }.toSet()
private val Numbers = mapOf("score" to 0..10, "num_chapters_read" to 0..Int.MAX_VALUE, "num_volumes_read" to 0..Int.MAX_VALUE)

internal fun JsonObject.projectMangaValues(
    authorization: String = "",
    clientId: String = "",
): JsonObject? {
    val projected = mutableMapOf<String, JsonPrimitive>()
    val secrets = listOf(authorization.removePrefix("Bearer "), clientId).filter(String::isNotBlank)
    for ((key, field) in this) {
        if (key != "status" && key !in Numbers) continue
        val value = field as? JsonPrimitive ?: return null
        val clean =
            if (key == "status") {
                value.takeIf { it.isString && it.content in StatusCodes } ?: return null
            } else {
                val number = value.intOrNull ?: return null
                if (value.isString || !ProjectionInteger.matches(value.content) || number !in Numbers.getValue(key)) return null
                JsonPrimitive(number)
            }
        if (secrets.any { key.contains(it, true) || value.content.contains(it, true) || clean.content.contains(it, true) }) return null
        projected[key] = clean
    }
    return JsonObject(projected)
}

private val ProjectionInteger = Regex("-?(?:0|[1-9][0-9]*)")

internal fun String.hasUniqueMangaMembers(): Boolean =
    try {
        isStrictMangaObject {
            val names = mutableSetOf<String>()
            val add: (String) -> Boolean = { raw -> names.add(MalJson.decodeFromString<String>(raw)) }
            add
        }
    } catch (_: SerializationException) {
        false
    }

internal fun String.uniqueMangaObject(): JsonObject? {
    if (!hasUniqueMangaMembers()) return null
    return try {
        MalJson.parseToJsonElement(this) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
}

internal fun JsonObject.agreeWith(form: Map<String, String>): Boolean =
    form.all { (key, value) -> key !in this || (get(key) as? JsonPrimitive)?.content == value }

internal fun JsonObject.matches(form: Map<String, String>): Boolean =
    "status" in this && form.all { (key, value) -> (get(key) as? JsonPrimitive)?.content == value }

internal fun mangaReply(
    name: String,
    status: Int,
    values: JsonElement?,
): Kept = Sanitiser(emptyMap()).privateAnswer(name, status, values)
