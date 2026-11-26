package io.github.kauri_off.kiroku.data.recorder

import org.junit.Assume.assumeTrue
import java.io.File

// Section 11.1: the signed-in recorder's answers as MAL sent them, in a folder outside every checkout; absent on GitHub.
internal object OwnerRecordings {
    const val FOLDER = "kiroku.ownerRecordings"

    val folder: File by lazy { ownerFolder(checkNotNull(System.getProperty(FOLDER)) { "The build names no folder in $FOLDER" }) }

    // The answer [name], or the test is skipped, saying what is missing and where it goes.
    fun named(name: String): RecordedAnswer {
        val file = folder.listFiles().orEmpty().singleOrNull { it.name.substringBefore('.') == name }
        assumeTrue("Skipped: no $name.<status>.json in $folder, where a signed-in recording is pulled (section 11.1)", file != null)
        return read(name) {
            check(!insideACheckout(file!!))
            RecordedAnswer(status = file.name.split('.')[1].toInt(), body = file.readText())
        }
    }

    fun mangaPages(): List<RecordedAnswer> {
        val required = java.lang.Boolean.getBoolean("$FOLDER.explicit")
        val files = read("Personal manga list capture") { folder.listFiles().orEmpty().toList() }
        val selected = mangaCaptureFiles(files, required)
        return read("Personal manga list capture") {
            selected.map { file ->
                check(!insideACheckout(file))
                RecordedAnswer(status = 200, body = file.readText())
            }
        }
    }

    // What [read] makes of the answer [name]; a failure names the answer and never a value of it.
    inline fun <T> read(
        name: String,
        read: () -> T,
    ): T =
        try {
            read()
        } catch (_: Exception) {
            throw AssertionError("$name could not be read")
        } catch (_: AssertionError) {
            throw AssertionError("$name could not be read")
        }
}

internal fun mangaCaptureFiles(
    files: List<File>,
    required: Boolean,
): List<File> {
    val pages = files.filter { it.name.startsWith("manga_list_") }
    val chosen =
        (1..5).map { page ->
            val name = "manga_list_$page"
            pages.singleOrNull { it.name.substringBefore('.') == name }?.takeIf { it.name == "$name.200.json" && it.isFile }
        }
    val complete = pages.size == chosen.size && chosen.all { it != null }
    if (required) {
        if (!complete) throw AssertionError("Personal manga list capture must contain five unique HTTP 200 pages")
    } else {
        assumeTrue("Skipped: personal manga list capture is absent or incomplete", complete)
    }
    return chosen.filterNotNull()
}

// The folder named by [path], where it really is; one in a checkout is refused.
internal fun ownerFolder(path: String): File {
    val folder = File(path).canonicalFile
    check(!insideACheckout(folder)) { "$folder is inside a git checkout: the signed-in recorder's answers stay outside every one" }
    return folder
}

// A folder in a checkout or in a worktree of one, where `git add` could reach what it holds, followed through links.
internal fun insideACheckout(folder: File): Boolean =
    generateSequence(folder.canonicalFile) { it.parentFile }.any { File(it, ".git").exists() }
