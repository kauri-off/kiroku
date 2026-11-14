package io.github.kauri_off.kiroku.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SourceChoiceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "source") }

    @Test
    fun `without a choice a build with a client id starts on the real account and one without on the demo`() {
        val choice = SourceChoice(file)
        assertNull(choice.chosen())
        assertEquals(DataSource.Account, choice.startsOn("0123abcd"))
        assertEquals(DataSource.Demo, choice.startsOn(""))
        assertEquals(DataSource.Demo, choice.startsOn("  "))
    }

    @Test
    fun `a choice is kept for the next process and holds whatever the client id`() {
        for (source in DataSource.entries) {
            assertTrue(SourceChoice(file).choose(source))
            val next = SourceChoice(file)
            assertEquals(source, next.chosen())
            assertEquals(source, next.startsOn("0123abcd"))
            assertEquals(source, next.startsOn(""))
            assertEquals(
                listOf("source"),
                folder.root
                    .list()
                    .orEmpty()
                    .toList(),
            )
        }
    }

    @Test
    fun `the file is the source's key alone, as a tool may write it, and one that names no source is no choice`() {
        file.writeText("demo\n")
        assertEquals(DataSource.Demo, SourceChoice(file).chosen())
        assertEquals(listOf("account", "demo"), DataSource.entries.map { it.key })
        for (text in listOf("", "Demo", "sandbox", "demo account")) {
            file.writeText(text)
            assertNull(text, SourceChoice(file).chosen())
        }
    }

    @Test
    fun `a choice that cannot be kept says so and leaves the one before`() {
        val choice = SourceChoice(file)
        assertTrue(choice.choose(DataSource.Demo))
        File(folder.root, "source.next").mkdirs()
        assertFalse(choice.choose(DataSource.Account))
        assertEquals(DataSource.Demo, choice.chosen())
    }
}
