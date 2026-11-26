package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.rules.projectDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Section 11.1: no release build holds the recorder. The build's guard reads the release classes, this the sources.
class RecorderGuardTest {
    private val released = listOf("app/src/main", "app/src/release", "data/src/main", "core/src/main").map { File(projectDir, it) }
    private val recorder = Regex("""\bkiroku\.(data\.)?recorder\b""")

    @Test
    fun `no source of a release build names the recorder`() {
        val sources = released.flatMap { root -> root.walk().filter { it.isFile && it.extension in setOf("kt", "xml") } }

        assertTrue(sources.size > 100)
        assertEquals(emptyList<String>(), sources.filter { recorder.containsMatchIn(it.readText()) }.map { it.relativeTo(projectDir).path })
    }

    @Test
    fun `the recorder's own sources are in the source sets only debug and preview builds compile`() {
        val declares = Regex("""^package io\.github\.kauri_off\.kiroku\.(data\.)?recorder$""", RegexOption.MULTILINE)
        val kotlin = projectDir.walk().onEnter { it.name != "build" && !it.name.startsWith(".") }.filter { it.extension == "kt" }
        val sets =
            kotlin
                .filter { it.isFile && declares.containsMatchIn(it.readText()) }
                .map {
                    it
                        .relativeTo(projectDir)
                        .path
                        .split('/')
                        .take(3)
                        .joinToString("/")
                }.toSet()

        assertEquals(setOf("app/src/debug", "app/src/testDebug", "data/src/debug", "data/src/test"), sets)
    }

    @Test
    fun `in a release build the graph's recorder is nothing`() {
        val release = File(projectDir, "app/src/release/kotlin/io/github/kauri_off/kiroku/wiring/LiveRecorder.kt").readText()

        assertTrue(release, release.trimEnd().endsWith("): Nothing? = null"))
        assertTrue(release, release.contains("fun liveMangaObserver(graph: LiveGraph): Nothing? = null"))
    }
}
