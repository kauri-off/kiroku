package io.github.kauri_off.kiroku.data.recorder

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// The title a recording puts on the user's list: on the disk before the first write, and until MAL has taken the title off.
internal class Journal(
    private val file: File,
) {
    // [user] names the user without holding the id MAL gives them.
    class Left(
        val title: RecordedTitle,
        val user: String,
    )

    // Null when no title is left.
    fun read(): Left? {
        val values =
            try {
                Json.parseToJsonElement(file.readText()) as? JsonObject
            } catch (_: IOException) {
                null
            } catch (_: SerializationException) {
                null
            } ?: return null
        val id = (values[ID] as? JsonPrimitive)?.longOrNull ?: return null
        val episodes = (values[EPISODES] as? JsonPrimitive)?.intOrNull ?: return null
        val name = (values[NAME] as? JsonPrimitive)?.content ?: return null
        val user = (values[USER] as? JsonPrimitive)?.content ?: return null
        return Left(RecordedTitle(id, name, episodes), user)
    }

    // On the disk when this returns: a write to the list never goes out before it.
    fun write(left: Left) {
        val text =
            buildJsonObject {
                put(ID, left.title.id)
                put(EPISODES, left.title.episodes)
                put(NAME, left.title.name)
                put(USER, left.user)
            }.toString()
        val next = File(file.path + NEXT)
        try {
            file.absoluteFile.parentFile?.mkdirs()
            FileOutputStream(next).use { output ->
                output.write(text.encodeToByteArray())
                output.fd.sync()
            }
            Files.move(next.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            next.delete()
        }
    }

    fun delete() {
        file.delete()
    }

    private companion object {
        const val ID = "id"
        const val EPISODES = "episodes"
        const val NAME = "name"
        const val USER = "user"
        const val NEXT = ".next"
    }
}
