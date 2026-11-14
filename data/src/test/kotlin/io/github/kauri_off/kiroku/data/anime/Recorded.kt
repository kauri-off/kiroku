package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// The recorded title pages, by the recorder's names.
internal val titlePages =
    listOf(
        "title_airing",
        "title_finished",
        "title_film",
        "title_not_yet_aired",
        "title_no_related",
        "title_unknown_episode_count",
        "title_adult_nsfw_true",
        "title_adult_nsfw_false",
        "title_adult_nsfw_unset",
    )

internal fun recordedJson(name: String): JsonObject = MalJson.parseToJsonElement(RecordedAnswers.named(name).body).jsonObject

internal fun recordedTitle(name: String): MalTitle = recordedJson(name).toTitle()

// Every title node of every recorded answer, the pages themselves included, each with the answer it came from.
internal val recordedNodes: List<Pair<String, JsonObject>> by lazy {
    RecordedAnswers.files.filter { it.name.endsWith(".json") }.flatMap { file ->
        val name = file.name.substringBefore('.')
        titleNodesIn(MalJson.parseToJsonElement(file.readText())).map { name to it }
    }
}

private fun titleNodesIn(element: JsonElement): List<JsonObject> =
    when (element) {
        is JsonObject ->
            listOfNotNull(element.takeIf { "id" in it && "title" in it && "status" in it }) +
                element.values.flatMap(::titleNodesIn)
        is JsonArray -> element.flatMap(::titleNodesIn)
        else -> emptyList()
    }

internal fun JsonObject.toNode(): AnimeNode = MalJson.decodeFromJsonElement(AnimeNode.serializer(), this)

// A recorded answer with one value at [path] removed, or replaced by [value]: for a shape no recording holds.
internal fun JsonObject.edited(
    path: List<String>,
    value: JsonElement? = null,
): JsonObject {
    val key = path.first()
    check(key in this) { "The recording has no $key" }
    if (path.size > 1) return JsonObject(this + (key to getValue(key).jsonObject.edited(path.drop(1), value)))
    return JsonObject(if (value == null) this - key else this + (key to value))
}
