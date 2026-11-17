package io.github.kauri_off.kiroku.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration

class MalBackOffTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = TestClock()
    private val reported = mutableListOf<Throwable>()
    private val file by lazy { File(folder.root, "back-off") }
    private val backOff by lazy { newProcess() }

    // The back-off a new process makes, over the same file.
    private fun newProcess() = MalBackOff(MalBackOff.file(file, clock, reported::add))

    private fun pass(time: Duration) {
        clock.now += time
    }

    @Test
    fun `background work may call MAL until MAL refuses`() {
        assertEquals(Duration.ZERO, backOff.remaining())
        backOff.refused(null)
        assertEquals(MalBackOff.First, backOff.remaining())
        pass(MalBackOff.First.minusSeconds(1))
        assertEquals(Duration.ofSeconds(1), backOff.remaining())
        pass(Duration.ofSeconds(1))
        assertEquals(Duration.ZERO, backOff.remaining())
    }

    @Test
    fun `a refusal after the wait ran out doubles the wait, up to the longest`() {
        backOff.refused(null)
        pass(MalBackOff.First)
        backOff.refused(null)
        assertEquals(MalBackOff.First.multipliedBy(2), backOff.remaining())
        repeat(10) {
            pass(backOff.remaining())
            backOff.refused(null)
        }
        assertEquals(MalBackOff.Longest, backOff.remaining())
    }

    @Test
    fun `refusals inside one wait start the same wait again`() {
        repeat(10) {
            backOff.refused(null)
            pass(Duration.ofSeconds(1))
        }
        backOff.refused(null)
        assertEquals(MalBackOff.First, backOff.remaining())
    }

    @Test
    fun `an answer ends the wait and the next refusal starts from the first wait`() {
        backOff.refused(null)
        pass(MalBackOff.First)
        backOff.refused(null)
        backOff.answered(backOff.mark())
        assertEquals(Duration.ZERO, backOff.remaining())
        backOff.refused(null)
        assertEquals(MalBackOff.First, backOff.remaining())
    }

    @Test
    fun `the time MAL names is waited for when it is longer, up to the longest`() {
        backOff.refused(Duration.ofHours(1))
        assertEquals(Duration.ofHours(1), backOff.remaining())
        backOff.answered(backOff.mark())
        backOff.refused(Duration.ofSeconds(5))
        assertEquals(MalBackOff.First, backOff.remaining())
        backOff.answered(backOff.mark())
        backOff.refused(Duration.ofDays(30))
        assertEquals(MalBackOff.Longest, backOff.remaining())
        backOff.answered(backOff.mark())
        backOff.refused(Duration.ofSeconds(-5))
        assertEquals(MalBackOff.First, backOff.remaining())
    }

    @Test
    fun `an answer to a request sent before the refusal does not end the wait`() {
        val before = backOff.mark()
        backOff.refused(null)
        backOff.answered(before)
        assertEquals(MalBackOff.First, backOff.remaining())
        backOff.answered(backOff.mark())
        assertEquals(Duration.ZERO, backOff.remaining())
    }

    @Test
    fun `a clock set back does not lengthen the wait`() {
        backOff.refused(null)
        pass(Duration.ofMinutes(5))
        clock.now -= Duration.ofDays(1)
        assertEquals(MalBackOff.First, backOff.remaining())
        pass(MalBackOff.First.minusSeconds(1))
        assertEquals(Duration.ofSeconds(1), backOff.remaining())
        pass(Duration.ofSeconds(1))
        assertEquals(Duration.ZERO, backOff.remaining())
    }

    @Test
    fun `a refusal holds a new process back for what is left of the wait, and no longer`() {
        backOff.refused(Duration.ofHours(1))
        assertEquals(Duration.ofHours(1), newProcess().remaining())
        pass(Duration.ofMinutes(59))
        assertEquals(Duration.ofMinutes(1), newProcess().remaining())
        pass(Duration.ofMinutes(1))
        assertEquals(Duration.ZERO, newProcess().remaining())
        assertEquals(emptyList<Throwable>(), reported)
    }

    @Test
    fun `a clock set back between processes does not lengthen the wait, for the processes after it either`() {
        backOff.refused(null)
        pass(Duration.ofMinutes(5))
        clock.now -= Duration.ofDays(1)
        assertEquals(MalBackOff.First, newProcess().remaining())
        pass(Duration.ofMinutes(10))
        assertEquals(MalBackOff.First.minusMinutes(10), newProcess().remaining())
        pass(Duration.ofMinutes(5))
        assertEquals(Duration.ZERO, newProcess().remaining())
    }

    @Test
    fun `a clock set back inside a process does not lengthen the wait for the next process`() {
        backOff.refused(null)
        clock.now -= Duration.ofDays(1)
        assertEquals(MalBackOff.First, backOff.remaining())
        pass(Duration.ofMinutes(10))
        assertEquals(MalBackOff.First.minusMinutes(10), newProcess().remaining())
    }

    // A real refusal is not ended early by a clock that was wrong for a while.
    @Test
    fun `a clock set back as a process starts and then put right leaves the wait as it was set`() {
        backOff.refused(MalBackOff.Longest)
        pass(Duration.ofMinutes(1))
        clock.now -= Duration.ofDays(1)
        assertEquals(MalBackOff.Longest, newProcess().remaining())
        pass(Duration.ofMinutes(1))
        clock.now += Duration.ofDays(1)
        assertEquals(MalBackOff.Longest.minusMinutes(2), newProcess().remaining())
        pass(MalBackOff.Longest.minusMinutes(2))
        assertEquals(Duration.ZERO, newProcess().remaining())
    }

    @Test
    fun `a clock set back and then put right inside one process leaves the wait as it was set`() {
        backOff.refused(MalBackOff.Longest)
        pass(Duration.ofMinutes(1))
        clock.now -= Duration.ofDays(1)
        assertEquals(MalBackOff.Longest, backOff.remaining())
        pass(Duration.ofMinutes(1))
        clock.now += Duration.ofDays(1)
        assertEquals(MalBackOff.Longest.minusMinutes(2), backOff.remaining())
        assertEquals(MalBackOff.Longest.minusMinutes(2), newProcess().remaining())
    }

    @Test
    fun `a clock set back a second time starts the wait once more, and no later process again`() {
        backOff.refused(null)
        clock.now -= Duration.ofDays(1)
        assertEquals(MalBackOff.First, newProcess().remaining())
        pass(Duration.ofMinutes(10))
        clock.now -= Duration.ofDays(1)
        assertEquals(MalBackOff.First, newProcess().remaining())
        pass(Duration.ofMinutes(10))
        assertEquals(MalBackOff.First.minusMinutes(10), newProcess().remaining())
    }

    @Test
    fun `a file as a build before the restart was kept wrote it is read`() {
        file.writeText("${clock.millis()} ${Duration.ofHours(1).toMillis()} ${MalBackOff.First.toMillis()}\n")
        pass(Duration.ofMinutes(10))
        val process = newProcess()
        assertEquals(Duration.ofMinutes(50), process.remaining())
        process.refused(null)
        assertEquals(MalBackOff.First, process.remaining())
        assertEquals(emptyList<Throwable>(), reported)
    }

    @Test
    fun `a new process doubles the wait of the one before, when that wait ran out without an answer`() {
        backOff.refused(null)
        pass(MalBackOff.First)
        val second = newProcess()
        second.refused(null)
        assertEquals(MalBackOff.First.multipliedBy(2), second.remaining())
        pass(Duration.ofMinutes(1))
        val third = newProcess()
        third.refused(null)
        assertEquals(MalBackOff.First.multipliedBy(2), third.remaining())
    }

    @Test
    fun `an answer in a new process ends the wait for every process after it`() {
        backOff.refused(null)
        assertTrue(file.isFile)
        val second = newProcess()
        second.answered(second.mark())
        assertEquals(Duration.ZERO, second.remaining())
        assertFalse(file.exists())
        val third = newProcess()
        assertEquals(Duration.ZERO, third.remaining())
        third.refused(null)
        assertEquals(MalBackOff.First, third.remaining())
    }

    @Test
    fun `nothing is kept while MAL does not refuse`() {
        backOff.answered(backOff.mark())
        assertEquals(Duration.ZERO, backOff.remaining())
        assertFalse(file.exists())
    }

    @Test
    fun `a file that cannot be read holds nothing back, is reported once and is gone`() {
        val longest = MalBackOff.Longest.toMillis()
        val texts =
            listOf(
                "soon",
                "1 2",
                "1 -2 3",
                "",
                "1 2 3 4 5",
                // A hold or a wait longer than any the app sets.
                "${clock.millis()} ${longest + 1} $longest",
                "${clock.millis()} $longest ${longest + 1}",
            )
        for (text in texts) {
            file.writeText(text)
            assertEquals(text, Duration.ZERO, newProcess().remaining())
            assertFalse(text, file.exists())
        }
        assertEquals(List(texts.size) { BackOffFileFault::class }, reported.map { it::class })
        assertEquals(Duration.ZERO, newProcess().remaining())
        assertEquals(texts.size, reported.size)

        // The longest of both is still read.
        file.writeText("${clock.millis()} $longest $longest")
        assertEquals(MalBackOff.Longest, newProcess().remaining())
    }

    @Test
    fun `a file that cannot be written is reported and the wait still holds in its process`() {
        file.mkdirs()
        File(file, "in the way").writeText("")
        backOff.refused(null)
        assertEquals(MalBackOff.First, backOff.remaining())
        assertTrue(reported.toString(), reported.any { it is UnusableAnswer })
        assertFalse(File(file.path + ".new").exists())
    }
}
