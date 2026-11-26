package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListNumber
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.core.repository.readingStatus
import io.github.kauri_off.kiroku.data.list.updatedAtOf
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.network.ReadAdmission
import io.github.kauri_off.kiroku.data.network.ReadDeferred
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.session.MalSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull

internal class MalMangaListSource(
    private val http: MalHttp,
    private val session: MalSession,
    retired: (SignInId) -> Boolean = { false },
) : MangaListSource,
    MangaListWriter,
    MangaListRemover {
    private val list = MalMangaList(http, session, retired)

    override suspend fun patch(
        owner: SignInId,
        id: MangaId,
        changes: MangaListEdit,
    ): MangaWriteResult {
        var failedOnHttp = false
        return when (val answer = list.patch(id, changes, owner) { failedOnHttp = true }) {
            is Outcome.Failure -> failure(answer.error, failedOnHttp)
            is Outcome.Success ->
                if (answer.value.acknowledgesPatch()) MangaWriteResult.Accepted else MangaWriteResult.Unknown(DataError.ServerError)
        }
    }

    override suspend fun remove(
        owner: SignInId,
        id: MangaId,
    ): MangaWriteResult {
        var failedOnHttp = false
        return when (val answer = list.delete(id, owner) { failedOnHttp = true }) {
            is Outcome.Success ->
                if (answer.value.status == 200 || answer.value.status == 204) {
                    MangaWriteResult.Accepted
                } else {
                    MangaWriteResult.Unknown(DataError.ServerError)
                }
            is Outcome.Failure ->
                if (failedOnHttp && answer.error == DataError.NotFound) {
                    MangaWriteResult.Unknown(DataError.NotFound)
                } else {
                    failure(answer.error, failedOnHttp)
                }
        }
    }

    private fun failure(
        error: DataError,
        failedOnHttp: Boolean,
    ): MangaWriteResult =
        if (failedOnHttp && (error == DataError.ServerError || error == DataError.NoConnection)) {
            MangaWriteResult.Unknown(error)
        } else {
            MangaWriteResult.Refused(error)
        }

    override suspend fun fetch(
        owner: SignInId,
        canDispatch: () -> Boolean,
    ): Outcome<List<MangaListEntry>>? {
        val answer =
            try {
                list.fetch(owner) {
                    when {
                        !session.isSignedInAs(owner) -> ReadAdmission.OwnerEnded
                        !canDispatch() -> ReadAdmission.Defer
                        else -> ReadAdmission.Send
                    }
                }
            } catch (_: ReadDeferred) {
                currentCoroutineContext().ensureActive()
                return null
            }
        return when (answer) {
            is Outcome.Failure -> answer
            is Outcome.Success -> {
                val repairs = mutableSetOf<String>()
                val entries = answer.value.rows.map { it.toEntry(repairs) }
                if (repairs.isNotEmpty()) http.unexpected(RepairedAnswer(repairs))
                Outcome.Success(entries)
            }
        }
    }
}

private fun MangaListRow.toEntry(repairs: MutableSet<String>): MangaListEntry {
    val node = fields.getValue("node").jsonObject
    val manga = node.toManga(id, repairs)
    val alternatives = node["alternative_titles"] as? JsonObject
    val synonyms =
        (alternatives?.get("synonyms") as? JsonArray).orEmpty().mapNotNull { value ->
            value.text()?.takeUnless(String::isBlank)
        }
    val values = fields.getValue("list_status").jsonObject

    fun <T : Any> read(
        key: String,
        parse: (JsonElement) -> T?,
    ): T? {
        val value = values[key]?.takeUnless { it is JsonNull } ?: return null
        return parse(value).also { if (it == null) repairs += "list_status.$key" }
    }
    if (MangaListStatus.entries.none { it.readingStatus.code == statusCode }) repairs += "list_status.status"
    val unverified = mutableSetOf<MangaListNumber>()

    fun number(
        key: String,
        field: MangaListNumber,
        range: IntRange,
    ): Int? =
        read(key) {
            val primitive = it as? JsonPrimitive ?: return@read null
            val number = primitive.intOrNull?.takeIf { n -> n in range }
            if (number != null && (primitive.isString || !JsonInteger.matches(primitive.content))) {
                unverified += field
                repairs += "list_status.$key"
            }
            number
        }

    val score = number("score", MangaListNumber.Score, 0..10)
    return MangaListEntry(
        id = id,
        titles = manga.titles.copy(synonyms = synonyms),
        status = MangaReadingStatus(statusCode),
        posterUrl = manga.posterUrl,
        chaptersRead = number("num_chapters_read", MangaListNumber.ChaptersRead, 0..Int.MAX_VALUE),
        volumesRead = number("num_volumes_read", MangaListNumber.VolumesRead, 0..Int.MAX_VALUE),
        score = score?.takeIf { it > 0 },
        explicitlyUnrated = score == 0,
        updatedAt = read("updated_at") { it.text()?.let(::updatedAtOf) },
        unverifiedNumbers = unverified.toSet(),
    )
}

private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private const val INTEGER = "-?(?:0|[1-9][0-9]*)"
private val JsonInteger = Regex(INTEGER)
private val JsonToken =
    Regex(
        """\G(?:[ \t\r\n]++|[{}\[\]:,]|"(?:[^"\\\x00-\x1F]++|\\(?:["\\/bfnrt]|u[0-9A-Fa-f]{4}))*+"""" +
            """|(?:true|false|null|$INTEGER(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)(?=$|[ \t\r\n,}\]]))""",
    )

private const val MAX_ACKNOWLEDGMENT_DEPTH = 64

private class JsonStructure(
    private val payload: String,
) {
    private var end = 0
    private val tokens =
        JsonToken
            .findAll(payload)
            .takeWhile { it.range.first == end }
            .onEach { end = it.range.last + 1 }
            .map { it.value }
            .filterNot { it[0] in " \t\r\n" }
            .iterator()

    private var token = next()

    fun isObject(): Boolean = token == "{" && value(0) && token == null && end == payload.length

    private fun next(): String? = if (tokens.hasNext()) tokens.next() else null

    private fun take(expected: String): Boolean {
        if (token != expected) return false
        token = next()
        return true
    }

    private fun value(depth: Int): Boolean {
        if (depth == MAX_ACKNOWLEDGMENT_DEPTH) return false
        return when (token) {
            "{" -> collection("}", ::takeMember, depth + 1)
            "[" -> collection("]", ::value, depth + 1)
            null, "}", "]", ":", "," -> false
            else -> {
                token = next()
                true
            }
        }
    }

    private fun takeMember(depth: Int): Boolean {
        if (token?.first() != '"') return false
        token = next()
        return take(":") && value(depth)
    }

    private fun collection(
        close: String,
        element: (Int) -> Boolean,
        depth: Int,
    ): Boolean {
        token = next()
        if (take(close)) return true
        do {
            if (!element(depth)) return false
        } while (take(","))
        return take(close)
    }
}

private fun MangaListWriteAnswer.acknowledgesPatch(): Boolean {
    if (status != 200 || mediaType?.toMediaTypeOrNull()?.subtype?.equals("json", ignoreCase = true) != true) return false
    val payload = body ?: return false
    if (!JsonStructure(payload).isObject()) return false
    return try {
        val document = MalJson.parseToJsonElement(payload) as? JsonObject
        document != null && document.isNotEmpty() && "error" !in document
    } catch (_: SerializationException) {
        false
    }
}
