package io.github.kauri_off.kiroku.source

import android.content.Context
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.wiring.DemoGraph
import io.github.kauri_off.kiroku.wiring.appGraph
import io.github.kauri_off.kiroku.wiring.malClientId
import java.io.File
import java.io.IOException

// What a debug or preview build runs on. Each has its own stores, and the demo keeps nothing and reaches no host.
enum class DataSource(
    val key: String,
) {
    Account(notShown("account")),
    Demo(notShown("demo")),
}

// The source the app starts on: kept in a file of one word, read when a process builds its graph.
class SourceChoice(
    private val file: File,
) {
    constructor(context: Context) : this(File(context.filesDir, FILE))

    // Null until a source was chosen, and for a file that names none.
    fun chosen(): DataSource? =
        try {
            val key = file.readText().trim()
            DataSource.entries.firstOrNull { it.key == key }
        } catch (_: IOException) {
            null
        }

    // Without a choice: the real account when the build has a client id, else the demo.
    fun startsOn(clientId: String): DataSource = chosen() ?: if (malClientId(clientId) == null) DataSource.Demo else DataSource.Account

    // False when the choice could not be kept; the file then holds what it held.
    fun choose(source: DataSource): Boolean =
        try {
            val next = File(file.path + NEXT)
            next.writeText(source.key)
            next.renameTo(file)
        } catch (_: IOException) {
            false
        }

    companion object {
        val FILE = notShown("source")
        private val NEXT = notShown(".next")
    }
}

// What this process runs on, whatever was chosen since it started.
val Context.runningSource: DataSource get() = if (appGraph is DemoGraph) DataSource.Demo else DataSource.Account
