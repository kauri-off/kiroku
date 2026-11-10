package io.github.kauri_off.kiroku.screenshots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// These clocks escape the screenshot runner's fixed moment, so app code must not read them.
class UnpinnedClocksTest {
    private val unpinned = Regex("""\bDate\(\)|\bGregorianCalendar\(|\bClock\.System\b""")

    @Test
    fun appCodeReadsNoUnpinnedClock() {
        val sources = File("src/main").walk().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        val uses = sources.flatMap { file -> file.readLines().filter { unpinned.containsMatchIn(it) }.map { "${file.name}: ${it.trim()}" } }
        assertEquals(emptyList<String>(), uses)
    }

    @Test
    fun patternCatchesEachUnpinnedClock() {
        listOf("Date()", "GregorianCalendar()", "kotlin.time.Clock.System.now()").forEach { assertTrue(it, unpinned.containsMatchIn(it)) }
        listOf("Date(millis)", "Clock.systemUTC()").forEach { assertTrue(it, !unpinned.containsMatchIn(it)) }
    }
}
