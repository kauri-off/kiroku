package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.core.repository.readingStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class MangaMutationMethod { Patch, Delete }

fun interface MangaMutationObserver {
    fun candidate(
        owner: SignInId,
        id: MangaId,
        method: MangaMutationMethod,
    ): MangaMutationClaim?
}

fun interface MangaMutationClaim {
    fun claim(): (suspend (MangaMutationEvidence?) -> Unit)?
}

class MangaMutationEvidence internal constructor(
    val status: Int,
    val write: JsonObject?,
    val readBack: JsonObject?,
    val pages: Int,
)

internal class MangaMutationExchange(
    private val id: MangaId,
    private val method: MangaMutationMethod,
) : AbstractCoroutineContextElement(Key) {
    private var status: Int? = null
    private var write: JsonObject? = null
    private var selected = false
    private var readBack: JsonObject? = null
    private var pages = 0
    private var terminal = false
    private var invalid = false
    var confirmed = false

    fun write(
        requested: MangaId,
        answer: MangaListWriteAnswer,
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
            val json = body.strictMangaObject()?.takeUnless { "error" in it } ?: return
            val values = json.project(authorization, clientId) ?: return
            if (values.isEmpty() && (json.isNotEmpty() || method == MangaMutationMethod.Patch)) return
            write = values
        }
        status = answer.status
    }

    fun page(
        body: String,
        entries: List<MangaListRow>,
        last: Boolean,
        authorization: String,
        clientId: String,
    ) {
        if (status == null || invalid) return
        if (body.length > MAX_PAGE_LENGTH || !body.isStrictMangaObject()) {
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
                .project(authorization, clientId)
    }

    fun completed(): MangaMutationEvidence? {
        val code = status ?: return null
        if (!confirmed || invalid || !terminal || pages <= 0) return null
        when (method) {
            MangaMutationMethod.Patch -> if (readBack?.isNotEmpty() != true) return null
            MangaMutationMethod.Delete -> if (selected) return null
        }
        return MangaMutationEvidence(code, write, readBack, pages)
    }

    companion object Key : CoroutineContext.Key<MangaMutationExchange> {
        private const val MAX_ACKNOWLEDGMENT_LENGTH = 16_384
        private const val MAX_PAGE_LENGTH = 4 * 1024 * 1024
    }
}

private val StatusCodes = MangaListStatus.entries.map { it.readingStatus.code }.toSet()
private val Numbers = mapOf("score" to 0..10, "num_chapters_read" to 0..Int.MAX_VALUE, "num_volumes_read" to 0..Int.MAX_VALUE)

private fun JsonObject.project(
    authorization: String,
    clientId: String,
): JsonObject? {
    val projected =
        filter { (key, field) ->
            val value = field as? JsonPrimitive ?: return@filter false
            if (key == "status") {
                value.isString && value.content in StatusCodes
            } else {
                val number = value.intOrNull
                !value.isString && number != null && number.toString() == value.content && Numbers[key]?.contains(number) == true
            }
        }
    val secrets = listOf(authorization.removePrefix("Bearer "), clientId).filter(String::isNotBlank)
    if (projected.any { (key, value) -> secrets.any { key.contains(it, true) || (value as JsonPrimitive).content.contains(it, true) } }) {
        return null
    }
    return JsonObject(projected)
}
