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

// A report as the debug menu shows it: when it was made, and the types of the log's report with their first frames.
data class Report(
    val at: Instant,
    val lines: List<String>,
)

// The latest reports, newest first: in memory, and from [keepIn] on in a file too, so they outlive the process.
class ReportLog(
    private val limit: Int = LIMIT,
    private val now: () -> Instant = Instant::now,
) {
    private val kept = MutableStateFlow(emptyList<Report>())
    val reports: StateFlow<List<Report>> = kept.asStateFlow()

    private var file: File? = null

    fun keepIn(file: File) =
        synchronized(this) {
            this.file = file
            update(kept.value + read(file))
        }

    fun keep(e: Throwable) = synchronized(this) { update(listOf(Report(now(), shortened(typesOf(e)))) + kept.value) }

    private fun update(reports: List<Report>) {
        kept.value = reports.take(limit)
        file?.let { write(it, kept.value) }
    }

    // A file that cannot be read or written loses the reports kept there; the log still has each one.
    private fun read(file: File): List<Report> =
        try {
            if (!file.exists()) return emptyList()
            file.readText().split(BETWEEN).filter { it.isNotBlank() }.map { block ->
                val lines = block.lines().filter { it.isNotEmpty() }
                Report(Instant.parse(lines.first()), lines.drop(1))
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: DateTimeException) {
            emptyList()
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
