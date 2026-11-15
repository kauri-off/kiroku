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
        return RecordedAnswer(status = file!!.name.split('.')[1].toInt(), body = file.readText())
    }

    // What [read] makes of the answer [name]; a failure names the answer and never a value of it.
    fun <T> read(
        name: String,
        read: () -> T,
    ): T =
        try {
            read()
        } catch (_: Exception) {
            throw AssertionError("$name could not be read")
        }
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
