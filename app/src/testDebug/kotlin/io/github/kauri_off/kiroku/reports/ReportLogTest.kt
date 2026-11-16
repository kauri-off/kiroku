package io.github.kauri_off.kiroku.reports

import io.github.kauri_off.kiroku.wiring.FRAME
import io.github.kauri_off.kiroku.wiring.typesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class ReportLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = Instant.parse("2026-11-16T08:00:00Z")

    private fun log(limit: Int = 20) = ReportLog(limit) { now.also { now = now.plusSeconds(60) } }

    private fun thrown(depth: Int = 0): Throwable = if (depth < 5) thrown(depth + 1) else IllegalStateException("Sousou no Frieren")

    @Test
    fun `a report is its types and the first three frames of each, with no message, newest first and the latest alone`() {
        val log = log(limit = 2)
        val first = thrown()
        val cause = IllegalArgumentException("watched 12")
        log.keep(first)
        log.keep(IllegalStateException(cause))
        log.keep(cause)

        val reports = log.reports.value
        assertEquals(listOf(Instant.parse("2026-11-16T08:02:00Z"), Instant.parse("2026-11-16T08:01:00Z")), reports.map { it.at })
        val types = typesOf(IllegalStateException(cause)).filterNot { it.startsWith(FRAME) }
        assertEquals(types, reports[1].lines.filterNot { it.startsWith(FRAME) })
        assertEquals(listOf(3, 3), reports[1].lines.runs())
        assertFalse(reports.flatMap { it.lines }.any { "watched" in it || "Frieren" in it })
    }

    @Test
    fun `reports kept in a file outlast the process, behind those of the new one`() {
        val file = File(folder.root, "reports")
        log().apply {
            keepIn(file)
            keep(thrown())
        }
        val earlier = log().apply { keepIn(file) }.reports.value

        val next = log()
        next.keep(IllegalArgumentException())
        val before = next.reports.value
        next.keepIn(file)

        assertEquals(1, earlier.size)
        assertEquals(before + earlier, next.reports.value)
        assertEquals(next.reports.value, log().apply { keepIn(file) }.reports.value)
    }

    @Test
    fun `a file that cannot be read or written keeps the reports in memory, and a report never fails`() {
        val directory = folder.newFolder("reports")
        val log = log().apply { keepIn(directory) }
        log.keep(thrown())
        assertEquals(1, log.reports.value.size)

        val garbled = folder.newFile("garbled").apply { writeText("not a time\nUnexpected: x") }
        assertTrue(
            log()
                .apply { keepIn(garbled) }
                .reports.value
                .isEmpty(),
        )
    }

    // The lengths of the runs of frames, in order.
    private fun List<String>.runs(): List<Int> =
        fold(mutableListOf<Int>()) { runs, line ->
            if (!line.startsWith(FRAME)) runs += 0 else runs[runs.lastIndex]++
            runs
        }.filter { it > 0 }
}
