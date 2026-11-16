package io.github.kauri_off.kiroku.reports

import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.wiring.FRAME
import io.github.kauri_off.kiroku.wiring.typesOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// A report as the debug menu shows it: when it was made, and the types of the log's report with their first frames.
data class Report(
    val at: Instant,
    val lines: List<String>,
) {
    init {
        require(lines.isNotEmpty())
    }
}

// The latest reports, newest first, in memory and from [keepIn] on in a file, which only [files] reads and writes.
class ReportLog(
    private val limit: Int = LIMIT,
    private val now: () -> Instant = Instant::now,
    private val files: Executor = Executors.newSingleThreadExecutor { Thread(it, NAME).apply { isDaemon = true } },
) {
    private val kept = MutableStateFlow(emptyList<Report>())
    val reports: StateFlow<List<Report>> = kept.asStateFlow()

    private var file: File? = null

    fun keepIn(file: File) =
        files.execute {
            val stored = read(file)
            synchronized(this) {
                this.file = file
                kept.value = (kept.value + stored).take(limit)
            }
            store()
        }

    // [then] runs once the report is in the file, or would have been, on the files' thread.
    fun keep(
        e: Throwable,
        then: () -> Unit = {},
    ) {
        synchronized(this) { kept.value = (listOf(Report(now(), shortened(typesOf(e)))) + kept.value).take(limit) }
        files.execute {
            store()
            then()
        }
    }

    private fun store() {
        val (file, reports) = synchronized(this) { file to kept.value }
        file?.let { write(it, reports) }
    }

    // A file or a block that cannot be read gives no report; the log still has each one.
    private fun read(file: File): List<Report> =
        try {
            if (!file.exists()) return emptyList()
            file.readText().split(BETWEEN).mapNotNull(::report)
        } catch (_: IOException) {
            emptyList()
        }

    private fun report(block: String): Report? {
        val lines = block.lines().filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        return try {
            Report(Instant.parse(lines.first()), lines.drop(1))
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun write(
        file: File,
        reports: List<Report>,
    ) {
        try {
            val next = File(file.path + NEXT)
            next.writeText(reports.joinToString(BETWEEN) { (listOf(it.at.toString()) + it.lines).joinToString(LINE) })
            if (!next.renameTo(file)) next.delete()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val LIMIT = 20
        const val FRAMES = 3
        val LINE = notShown("\n")
        val BETWEEN = LINE + LINE
        val NEXT = notShown(".next")
        val NAME = notShown("reports")

        // Each type with its first frames; the log has every frame.
        fun shortened(lines: List<String>): List<String> {
            var frames = 0
            return lines.filter { line ->
                if (line.startsWith(FRAME)) {
                    frames++ < FRAMES
                } else {
                    frames = 0
                    true
                }
            }
        }
    }
}

// The reports of this process's reporter, which the debug menu shows.
internal val reportLog = ReportLog()
