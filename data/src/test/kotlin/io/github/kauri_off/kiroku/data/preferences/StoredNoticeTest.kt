package io.github.kauri_off.kiroku.data.preferences

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class StoredNoticeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file get() = File(folder.root, "links.preferences_pb")
    private val reports = CopyOnWriteArrayList<Throwable>()

    // One process over the file: its store ends with it, as when the process ends.
    private fun <T> process(block: suspend (StoredNotice) -> T): T =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            try {
                block(StoredNotice(file, scope, reports::add))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    @Test
    fun `a notice is not shown until marked, and a new process over the same file reads it as shown`() {
        assertFalse(process { it.shown() })
        process { it.markShown() }
        assertTrue(process { it.shown() })
        assertEquals(emptyList<Throwable>(), reports)
    }

    @Test
    fun `a file that cannot be read means the notice was not shown, is reported, and can be marked again`() {
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertFalse(process { it.shown() })
        assertTrue(reports.single() is PreferencesFailure)
        process { it.markShown() }
        assertTrue(process { it.shown() })
    }

    @Test
    fun `a folder where the file should be is reported, and nothing is shown or kept`() {
        file.mkdirs()
        assertFalse(process { it.shown() })
        process { it.markShown() }
        assertFalse(process { it.shown() })
        assertTrue(reports.isNotEmpty())
        assertTrue(reports.all { it is PreferencesFailure })
    }
}
