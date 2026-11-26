package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.core.repository.readingStatus
import io.github.kauri_off.kiroku.data.manga.MangaListRow
import io.github.kauri_off.kiroku.data.manga.MangaListWriteAnswer
import io.github.kauri_off.kiroku.data.manga.MangaMutationExchange
import io.github.kauri_off.kiroku.data.manga.MangaMutationMethod
import io.github.kauri_off.kiroku.data.manga.isStrictMangaObject
import io.github.kauri_off.kiroku.data.network.MalJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal class MangaMutationEvidence(
    val status: Int,
    val write: JsonObject?,
    val readBack: JsonObject?,
    val pages: Int,
)

internal class ObservedMangaMutation(
    private val id: MangaId,
    private val method: MangaMutationMethod,
    private val complete: suspend (MangaMutationEvidence?) -> Unit,
) : MangaMutationExchange() {
    private var status: Int? = null
    private var write: JsonObject? = null
    private var selected = false
    private var readBack: JsonObject? = null
    private var pages = 0
    private var terminal = false
    private var invalid = false
    private var expected = emptyMap<String, String>()

    override fun write(
        requested: MangaId,
        answer: MangaListWriteAnswer,
        form: Map<String, String>,
        authorization: String,
        clientId: String,
    ) {
        if (id != requested) return
        val body = answer.body.orEmpty()
        when (method) {
            MangaMutationMethod.Patch -> if (answer.status != 200 || body.isEmpty()) return
            MangaMutationMethod.Delete -> if (answer.status != 200 && answer.status != 204) return
        }
        if (body.isNotEmpty()) {
            if (body.length > MAX_ACKNOWLEDGMENT_LENGTH) return
            val json = body.uniqueMangaObject()?.takeUnless { "error" in it } ?: return
            val values = json.projectMangaValues(authorization, clientId) ?: return
            if (values.isEmpty() && (json.isNotEmpty() || method == MangaMutationMethod.Patch)) return
            if (form.any { (key, value) -> key in values && values[key]?.let { (it as JsonPrimitive).content } != value }) return
            write = values
        }
        expected = form
        status = answer.status
    }

    override fun page(
        body: String,
        entries: List<MangaListRow>,
        last: Boolean,
        authorization: String,
        clientId: String,
    ) {
        if (status == null || invalid) return
        if (body.length > MAX_PAGE_LENGTH || !body.hasUniqueMangaMembers()) {
            invalid = true
            return
        }
        pages++
        terminal = last
        val row = entries.firstOrNull { it.id == id } ?: return
        selected = true
        readBack =
            row.fields
                .getValue("list_status")
                .jsonObject
                .projectMangaValues(authorization, clientId)
    }

    override suspend fun finish() {
        complete(evidence())
    }

    private fun evidence(): MangaMutationEvidence? {
        val code = status ?: return null
        if (!confirmed || invalid || !terminal || pages <= 0) return null
        when (method) {
            MangaMutationMethod.Patch -> {
                val returned = readBack ?: return null
                if (expected.isEmpty() || !expected.all { (key, value) -> (returned[key] as? JsonPrimitive)?.content == value }) return null
            }
            MangaMutationMethod.Delete -> if (selected) return null
        }
        return MangaMutationEvidence(code, write, readBack, pages)
    }

    private companion object {
        private const val MAX_ACKNOWLEDGMENT_LENGTH = 16_384
        private const val MAX_PAGE_LENGTH = 4 * 1024 * 1024
    }
}

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
