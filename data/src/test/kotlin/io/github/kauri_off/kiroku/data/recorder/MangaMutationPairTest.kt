package io.github.kauri_off.kiroku.data.recorder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class MangaMutationPairTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `validate selected private mutation evidence`() {
        val pair = System.getProperty("kiroku.mangaMutationCapture")
        val expected = System.getProperty("kiroku.mangaMutationExpected")
        assumeTrue("No manga mutation capture selected", pair != null || expected != null)
        OwnerRecordings.read("Selected manga mutation pair") {
            check(pair != null)
            validateMangaMutationPair(ownerFolder(pair), expected?.let(::ownerFolder))
        }
    }

    @Test
    fun `validates complete patch and both supported deletion statuses`() {
        val patch = pair("manga_mutation_patch.200.json", """{"status":"reading"}""", PATCH)
        val expected = temporary.newFile().apply { writeText("""{"score":0,"status":"reading"}""") }
        validateMangaMutationPair(patch, expected)
        validateMangaMutationPair(pair("manga_mutation_delete.204.txt", "204\n", REMOVED), null)
        validateMangaMutationPair(pair("manga_mutation_delete.200.json", "{}", REMOVED), null)
    }

    @Test
    fun `rejects missing contradictory ambiguous and overbroad evidence`() {
        val expected = temporary.newFile().apply { writeText("""{"score":0}""") }
        val expectedProgress = temporary.newFile().apply { writeText("""{"score":0,"num_volumes_read":3}""") }
        val valid = pair("manga_mutation_patch.200.json", """{"score":0}""", PATCH)
        for (operation in listOf<() -> Unit>(
            { validateMangaMutationPair(valid, null) },
            { validateMangaMutationPair(valid, expectedProgress) },
            { validateMangaMutationPair(temporary.newFolder(), expected) },
            { validateMangaMutationPair(pair("manga_mutation_patch.200.json", """{"score":1}""", PATCH), expected) },
            { validateMangaMutationPair(pair("manga_mutation_patch.200.json", """{"score":1,"score":0}""", PATCH), expected) },
            { validateMangaMutationPair(pair("manga_mutation_patch.200.json", """{"score":0,"extra":"fixture-only"}""", PATCH), expected) },
            { validateMangaMutationPair(pair("manga_mutation_delete.404.txt", "404\n", REMOVED), null) },
            { validateMangaMutationPair(pair("manga_mutation_delete.204.txt", "204\n", PATCH), null) },
        )) {
            assertThrows(IllegalStateException::class.java) { operation() }
        }
    }

    @Test
    fun `a patch read back requires a known status even when the expected edit only changes score`() {
        val expected = temporary.newFile().apply { writeText("""{"score":0}""") }
        val patch = "manga_mutation_patch.200.json"
        val written = """{"score":0}"""
        val missing = PATCH.replace("\"status\":\"reading\",", "")
        val unknown = PATCH.replace("reading", "unknown")
        validateMangaMutationPair(pair(patch, written, PATCH), expected)

        for (invalid in listOf(missing, unknown)) {
            assertThrows(IllegalStateException::class.java) { validateMangaMutationPair(pair(patch, written, invalid), expected) }
        }
    }

    private fun pair(
        write: String,
        body: String,
        readBack: String,
    ): File =
        temporary.newFolder().apply {
            File(this, write).writeText(body)
            File(this, READ_BACK).writeText(readBack)
            markCaptureComplete(this)
        }

    private companion object {
        const val PATCH = """{"pages":2,"complete":true,"present":true,"list_status":{"status":"reading","score":0}}"""
        const val REMOVED = """{"pages":1,"complete":true,"present":false}"""
    }
}

private const val READ_BACK = "manga_mutation_read_back.200.json"

internal fun validateMangaMutationPair(
    folder: File,
    expected: File?,
) {
    val files = checkNotNull(folder.listFiles()).associateBy { it.name }.toMutableMap()
    val marker = checkNotNull(files.remove(MANGA_CAPTURE_COMPLETE))
    check(Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS) && marker.length() == 0L)
    check(files.size == 2)
    val read = checkNotNull(files[READ_BACK]).objectBody()
    val pageCount = read["pages"] as? JsonPrimitive
    check(pageCount != null && !pageCount.isString && pageCount.intOrNull?.let { it in 1..100_000 } == true)
    check(read["complete"] == JsonPrimitive(true))

    val (name, reply) = files.entries.single { it.key != READ_BACK }
    if (name == "manga_mutation_patch.200.json") {
        val write = reply.objectBody().verifiedFields().also { check(it.isNotEmpty()) }
        check(read.keys == setOf("pages", "complete", "present", "list_status") && read["present"] == JsonPrimitive(true))
        val values = (read["list_status"] as? JsonObject)?.verifiedFields() ?: error("Invalid projection")
        check("status" in values)
        val changes = checkNotNull(expected).objectBody().verifiedFields().also { check(it.isNotEmpty()) }
        check(changes.all { (field, value) -> values[field] == value && (field !in write || write[field] == value) })
    } else {
        check(expected == null && read.keys == setOf("pages", "complete", "present") && read["present"] == JsonPrimitive(false))
        when (name) {
            "manga_mutation_delete.200.json" -> reply.objectBody().verifiedFields()
            "manga_mutation_delete.200.txt" -> check(reply.safeBody() == "200\n")
            "manga_mutation_delete.204.txt" -> check(reply.safeBody() == "204\n")
            else -> error("Invalid mutation reply")
        }
    }
}

private fun JsonObject.verifiedFields(): JsonObject = apply { check(projectMangaValues() == this) }

private fun File.objectBody(): JsonObject = checkNotNull(safeBody().uniqueMangaObject())

private fun File.safeBody(): String {
    check(
        isFile &&
            length() in 1..16_384 &&
            !insideACheckout(this) &&
            !java.nio.file.Files
                .isSymbolicLink(toPath()),
    )
    return readText()
}
