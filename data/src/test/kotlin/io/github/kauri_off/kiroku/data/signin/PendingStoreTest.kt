package io.github.kauri_off.kiroku.data.signin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.newFolder(), "sign-in") }

    // One store at a time over the file, as one process holds it.
    private fun <T> stored(block: suspend (PendingStore) -> T): T =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.IO)
            try {
                block(PendingStore(file, scope))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    private fun PendingSignIn?.text(): String? = this?.run { "$state $verifier $startedAt" }

    @Test
    fun whatWasWrittenIsReadByTheNextProcessUntilItIsTakenAway() {
        assertNull(stored { it.read() })
        assertTrue(stored { it.write(PendingSignIn("state-1", "verifier-1", 1_700_000_000)) })
        assertEquals("state-1 verifier-1 1700000000", stored { it.read() }.text())
        assertTrue(stored { it.write(PendingSignIn("state-2", "verifier-2", 5)) })
        assertEquals("state-2 verifier-2 5", stored { it.read() }.text())
        assertTrue(stored { it.write(null) })
        assertNull(stored { it.read() })
        assertEquals(0, file.length())
    }

    @Test
    fun aFileThatIsNotAWaitingSignInIsNone() {
        val contents =
            listOf(
                "",
                "state",
                "state\nverifier",
                "state\nverifier\nsoon",
                "\nverifier\n5",
                "state\n\n5",
                "state\nverifier\n5\n",
                "\u0000\u0001",
            )
        for (content in contents) {
            file.writeText(content)
            assertNull(content, stored { it.read() })
        }
    }

    @Test
    fun aFileThatCannotBeReadIsNoneAndTheNextWriteReplacesIt() {
        assertTrue(file.mkdir())
        assertNull(stored { it.read() })
        assertTrue(stored { it.write(PendingSignIn("state-1", "verifier-1", 7)) })
        assertEquals("state-1 verifier-1 7", stored { it.read() }.text())
    }

    @Test
    fun aWriteThatFailsSaysSo() {
        assertTrue(file.mkdir())
        assertTrue(File(file, "kept").createNewFile())
        assertFalse(stored { it.write(PendingSignIn("state-1", "verifier-1", 7)) })
        assertNull(stored { it.read() })
    }
}
