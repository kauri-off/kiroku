package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.manga.uniqueMangaObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

class MangaMutationCaptureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `validate selected private mutation evidence`() {
        val selected = System.getProperty("kiroku.mangaMutationCapture")
        assumeNotNull(selected)
        OwnerRecordings.read("Selected manga mutation capture") {
            validateMangaMutationCapture(ownerFolder(checkNotNull(selected)))
        }
    }

    @Test
    fun `validator accepts complete evidence with either supported deletion status`() {
        for (deletion in listOf("200.json" to "null", "200.json" to "{}", "200.txt" to "200\n", "204.txt" to "204\n")) {
            val folder = example()
            File(folder, "$MANGA_REMOVED.${deletion.first}").writeText(deletion.second)
            validateMangaMutationCapture(folder)
        }
    }

    @Test
    fun `validator rejects incomplete contradictory or excessive evidence`() {
        val invalid =
            listOf<(File) -> Unit>(
                { it.resolve(MANGA_CAPTURE_COMPLETE).delete() },
                { it.resolve("$MANGA_ABSENT.200.json").writeText(PRESENT) },
                { it.resolve("manga_edit_read.200.json").writeText(ABSENT) },
                { it.resolve("manga_edit_read.200.json").writeText(PRESENT.replace("7", "8")) },
                { it.resolve("manga_edit.200.json").writeText("""{"score":8}""") },
                { it.resolve("manga_edit.200.json").writeText("""{"score":8,"score":7}""") },
                { it.resolve("manga_edit.200.json").writeText("""{"score":7,"extra":"fixture-only"}""") },
                { it.resolve("$MANGA_REMOVED_READ.200.json").writeText(PRESENT) },
                { it.resolve("$MANGA_REMOVED.204.txt").writeText("200\n") },
                { it.resolve("extra.txt").writeText("fixture-only") },
            )
        for (alter in invalid) {
            val folder = example()
            File(folder, "$MANGA_REMOVED.204.txt").writeText("204\n")
            alter(folder)
            assertThrows(IllegalStateException::class.java) { validateMangaMutationCapture(folder) }
        }
    }

    @Test
    fun `a staging directory is never a completed capture even with its marker`() {
        val folder = example()
        folder.resolve("$MANGA_REMOVED.204.txt").writeText("204\n")
        val staging = temporary.root.resolve("run-fixture")
        check(folder.renameTo(staging))
        assertThrows(IllegalStateException::class.java) { validateMangaMutationCapture(staging) }
    }

    @Test
    fun `validator rejects off-form add and edit version contradictions and pre-edit drift`() {
        val invalid =
            listOf(
                "manga_add" to """{"status":"plan_to_read","score":0}""",
                "manga_edit" to """{"score":7,"updated_at":"2026-11-25T10:03:00Z"}""",
                MANGA_BEFORE_EDIT to PLANNED.replace("10:01", "10:00"),
            )
        for ((name, body) in invalid) {
            val folder = example()
            folder.resolve("$MANGA_REMOVED.204.txt").writeText("204\n")
            folder.resolve("$name.200.json").writeText(body)
            assertThrows(IllegalStateException::class.java) { validateMangaMutationCapture(folder) }
        }
    }

    private fun example(): File =
        temporary.root.resolve("capture-${java.util.UUID.randomUUID()}").apply {
            check(mkdir())
            resolve(MANGA_CAPTURE_COMPLETE).createNewFile()
            for ((name, body) in mapOf(
                MANGA_ABSENT to ABSENT,
                "manga_add" to """{"status":"plan_to_read"}""",
                "manga_add_read" to PLANNED,
                MANGA_BEFORE_EDIT to PLANNED,
                "manga_edit" to """{"score":7}""",
                "manga_edit_read" to PRESENT,
                MANGA_REMOVED_READ to ABSENT,
            )) {
                resolve("$name.200.json").writeText(body)
            }
        }

    private companion object {
        const val ABSENT = """{"pages":1,"complete":true,"present":false}"""
        const val EDIT = """"status":"reading","score":7,"num_chapters_read":1,"num_volumes_read":1,"updated_at":"2026-11-25T10:02:00Z""""
        const val PRESENT = """{"pages":2,"complete":true,"present":true,"list_status":{$EDIT}}"""
        const val PLANNED =
            """{"pages":2,"complete":true,"present":true,"list_status":{"status":"plan_to_read","score":8,"updated_at":"2026-11-25T10:01:00Z"}}"""
    }
}

internal fun validateMangaMutationCapture(folder: File) {
    check(folder.name.startsWith("capture-"))
    val files = checkNotNull(folder.listFiles()).associateBy { it.name }.toMutableMap()
    val marker = checkNotNull(files.remove(MANGA_CAPTURE_COMPLETE))
    check(Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.length() == 0L)

    fun absent(name: String) {
        val read = checkNotNull(files.remove("$name.200.json")).membership(present = false)
        check(read.keys == setOf("pages", "complete", "present"))
    }

    fun checkpoint(name: String): JsonObject {
        val read = checkNotNull(files.remove("$name.200.json")).membership(present = true)
        check(read.keys == setOf("pages", "complete", "present", "list_status"))
        val values = (read["list_status"] as? JsonObject)?.verifiedCheckpoint() ?: error("Invalid projection")
        check(MANGA_VERSION in values)
        return values
    }

    absent(MANGA_ABSENT)
    var previous: JsonObject? = null
    for ((name, form) in MangaWrites) {
        if (previous != null) check(checkpoint(MANGA_BEFORE_EDIT) == previous)
        val write = checkNotNull(files.remove("$name.200.json")).objectBody().verifiedCheckpoint()
        check(write.keys.any { it != MANGA_VERSION } && write.agreeWith(form))
        val read = checkpoint("${name}_read")
        check(read.matches(form) && write.agreesWithReadBack(read))
        previous = read
    }
    absent(MANGA_REMOVED_READ)
    val (name, reply) = files.entries.singleOrNull() ?: error("Expected one deletion reply")
    when (name) {
        "$MANGA_REMOVED.200.json" -> reply.safeBody().takeUnless { it == "null" }?.let { reply.verifiedFields() }
        "$MANGA_REMOVED.200.txt" -> check(reply.safeBody() == "200\n")
        "$MANGA_REMOVED.204.txt" -> check(reply.safeBody() == "204\n")
        else -> error("Invalid deletion reply")
    }
}

private fun File.membership(present: Boolean): JsonObject =
    objectBody().apply {
        val count = get("pages") as? JsonPrimitive
        check(count != null && !count.isString && count.intOrNull?.let { it in 1..50 } == true)
        check(get("complete") == JsonPrimitive(true) && get("present") == JsonPrimitive(present))
    }

private fun JsonObject.verifiedCheckpoint(): JsonObject = apply { check(projectMangaCheckpoint() == this) }

private fun JsonObject.verifiedFields(): JsonObject = apply { check(projectMangaValues() == this) }

private fun File.verifiedFields(): JsonObject = objectBody().verifiedFields()

private fun File.objectBody(): JsonObject = checkNotNull(safeBody().uniqueMangaObject())

private fun File.safeBody(): String {
    check(Files.isRegularFile(toPath(), NOFOLLOW_LINKS) && length() in 1..16_384 && !insideACheckout(this))
    return readText()
}
