package io.github.kauri_off.kiroku.data.recorder

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Both recorders leave MAL this long between two requests.
internal const val PAUSE_MILLIS = 2000L

// What is stored of one answer.
internal class Kept(
    val file: String,
    val text: String,
)

// A body that is not JSON could hold a name in a form the sanitiser does not know: only what kind it was is stored.
internal fun Sanitiser.kept(
    name: String,
    status: Int,
    type: String?,
    json: String?,
): Kept = Kept("$name.$status.${if (json != null) "json" else "txt"}", json ?: plain("$status, ${type ?: "no content type"}\n"))

// A stored answer as the rules of its kind leave it (an own-list page, a recorder's own title, any other); null when not JSON.
internal fun cleanedStored(
    file: String,
    text: String,
): String? {
    val name = file.substringBefore('.')
    val page =
        OwnListPage
            .matchEntire(name)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    if (page != null) return Sanitiser(emptyMap()).ownList(text, (page - 1) * KEPT_ENTRIES)
    val answer =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
    val own = (answer?.get("id") as? JsonPrimitive)?.longOrNull.takeIf { name in OwnEntryAnswers }
    return Sanitiser(emptyMap(), ownTitle = own).json(text)
}

private val OwnListPage = Regex("""own_list_(\d+)""")

// Where a run's answers wait: empty before the run's first request.
internal fun emptied(run: File): Boolean {
    run.mkdirs()
    run.listFiles()?.forEach { it.delete() }
    return run.isDirectory && run.listFiles().isNullOrEmpty()
}

// A run that has ended: its answers take the place of the earlier ones of their names. A run that stopped is never kept.
internal fun keep(
    run: File,
    into: File,
) {
    into.mkdirs()
    for (answer in run.listFiles().orEmpty().sortedBy { it.name }) {
        into.listFiles { earlier -> earlier.name.substringBefore('.') == answer.name.substringBefore('.') }?.forEach { it.delete() }
        Files.move(answer.toPath(), File(into, answer.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    run.delete()
}
