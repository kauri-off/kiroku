package io.github.kauri_off.kiroku.data.recorder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

// The title a recording puts on the user's list: on the disk before the first write, and until MAL has taken the title off.
internal class Journal(
    private val file: File,
    // Makes a rename in a directory last through a loss of power.
    private val synced: (File) -> Unit = ::syncedDirectory,
) {
    data class Left(
        val title: RecordedTitle,
        val user: String,
        val mangaProof: JsonObject? = null,
    )

    class Unreadable : IOException()

    fun read(): Left? {
        val path = file.toPath()
        if (Files.notExists(path, NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw Unreadable()
        val text =
            try {
                Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                    InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()).buffered().readText()
                }
            } catch (_: IOException) {
                if (Files.notExists(path, NOFOLLOW_LINKS)) return null
                throw Unreadable()
            } catch (_: SecurityException) {
                throw Unreadable()
            }
        val values = text.uniqueMangaObject() ?: throw Unreadable()
        val id = (values[ID] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: throw Unreadable()
        val episodes = (values[EPISODES] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: throw Unreadable()
        val name = values.mangaText(NAME) ?: throw Unreadable()
        val user = values.mangaText(USER) ?: throw Unreadable()
        val media =
            if (MEDIA in values) {
                val value = values.mangaText(MEDIA)
                RecorderMedia.entries.singleOrNull { it.name == value } ?: throw Unreadable()
            } else {
                RecorderMedia.Anime
            }
        val proof =
            if (PROOF in values) {
                if (media != RecorderMedia.Manga) throw Unreadable()
                val row = values[PROOF] as? JsonObject ?: throw Unreadable()
                val projected = row.projectMangaValues() ?: throw Unreadable()
                if (row.mangaProof(projected) != row || MangaWrites.none { (_, form) -> projected.matches(form) }) throw Unreadable()
                row
            } else {
                null
            }
        return Left(RecordedTitle(id, name, episodes, media), user, proof)
    }

    // On the disk when this returns: a write to the list never goes out before it.
    fun write(left: Left) {
        val text =
            buildJsonObject {
                put(ID, left.title.id)
                put(EPISODES, left.title.episodes)
                put(NAME, left.title.name)
                put(USER, left.user)
                if (left.title.media != RecorderMedia.Anime) put(MEDIA, left.title.media.name)
                left.mangaProof?.let { put(PROOF, it) }
            }.toString()
        val next = File(file.path + NEXT)
        try {
            file.absoluteFile.parentFile?.mkdirs()
            FileOutputStream(next).use { output ->
                output.write(text.encodeToByteArray())
                output.fd.sync()
            }
            Files.move(next.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            file.absoluteFile.parentFile?.let(synced)
        } finally {
            next.delete()
        }
    }

    fun delete() {
        file.delete()
    }

    fun deleteConfirmed() {
        Files.deleteIfExists(file.toPath())
        file.absoluteFile.parentFile?.let(synced)
    }

    private companion object {
        const val PROOF = "manga_row"
        const val MEDIA = "media"
        const val ID = "id"
        const val EPISODES = "episodes"
        const val NAME = "name"
        const val USER = "user"
        const val NEXT = ".next"
    }
}

// A system that cannot sync a directory leaves the rename as durable as the system makes it.
internal fun syncedDirectory(directory: File) {
    try {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        return
    } catch (_: UnsupportedOperationException) {
        return
    }
}
