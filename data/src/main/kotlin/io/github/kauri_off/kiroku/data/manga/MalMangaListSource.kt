package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.core.repository.readingStatus
import io.github.kauri_off.kiroku.data.list.updatedAtOf
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.ReadAdmission
import io.github.kauri_off.kiroku.data.network.ReadDeferred
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.session.MalSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal class MalMangaListSource(
    private val http: MalHttp,
    private val session: MalSession,
) : MangaListSource,
    MangaListWriter {
    private val list = MalMangaList(http, session)

    override suspend fun patch(
        owner: SignInId,
        id: MangaId,
        changes: MangaListEdit,
    ): Outcome<Unit> =
        when (val answer = list.patch(id, changes, owner)) {
            is Outcome.Failure -> answer
            is Outcome.Success -> Outcome.Success(Unit)
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
    return MangaListEntry(
        id = id,
        titles = manga.titles.copy(synonyms = synonyms),
        status = MangaReadingStatus(statusCode),
        posterUrl = manga.posterUrl,
        chaptersRead = read("num_chapters_read") { it.number()?.takeIf { n -> n >= 0 } },
        volumesRead = read("num_volumes_read") { it.number()?.takeIf { n -> n >= 0 } },
        score = read("score") { it.number()?.takeIf { n -> n in 0..10 } }?.takeIf { it > 0 },
        updatedAt = read("updated_at") { it.text()?.let(::updatedAtOf) },
    )
}

private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.number(): Int? = (this as? JsonPrimitive)?.intOrNull
