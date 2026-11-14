package io.github.kauri_off.kiroku.data.recorder

import java.io.File

// A recorded answer of MAL's: the status and the body, as a recorder stored them.
internal class RecordedAnswer(
    val status: Int,
    val body: String,
)

// The answers under `mal/recorded`, each stored as `<name>.<status>.json`, or `.txt` when the body is not JSON.
internal object RecordedAnswers {
    val files: List<File>
        get() = File(checkNotNull(javaClass.getResource("/mal/recorded")).toURI()).listFiles().orEmpty().sortedBy { it.name }

    fun named(name: String): RecordedAnswer {
        val file = files.single { it.name.substringBefore('.') == name }
        return RecordedAnswer(status = file.name.split('.')[1].toInt(), body = file.readText())
    }
}
