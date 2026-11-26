package io.github.kauri_off.kiroku.data.recorder

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

// A stored answer as the sanitiser leaves it; null when it is not JSON or is a signed-in answer, which is kept as MAL sent it.
internal fun cleanedStored(
    file: String,
    text: String,
): String? = if (isSignedInAnswer(file.substringBefore('.'))) null else Sanitiser(emptyMap()).json(text)

// A signed-in answer as MAL sent it, without [Sanitiser]'s secrets; stored as `.json` when it is JSON.
internal fun Sanitiser.asSent(
    name: String,
    status: Int,
    body: String,
): Kept {
    val json =
        try {
            Json.parseToJsonElement(body).let { it is JsonObject || it is JsonArray }
        } catch (_: SerializationException) {
            false
        }
    return Kept("$name.$status.${if (json) "json" else "txt"}", asSent(body))
}

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

internal fun storePrivateAnswer(
    pending: File,
    kept: Kept,
    write: (File, String) -> Unit,
    tempDirectory: File = checkNotNull(pending.parentFile),
) {
    val temp = Files.createTempFile(tempDirectory.toPath(), "answer-", ".tmp").toFile()
    try {
        write(temp, kept.text)
        Files.move(temp.toPath(), File(pending, kept.file).toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally {
        temp.delete()
    }
}
