package io.github.kauri_off.kiroku.link

import io.github.kauri_off.kiroku.data.preferences.StoredNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeviceLinkNoticeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var consentNeeded = true

    // One process over the notice's file.
    private fun <T> process(block: suspend (LinkNotice) -> T): T =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            try {
                block(DeviceLinkNotice(StoredNotice(File(folder.root, "links"), scope, {})) { consentNeeded })
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    @Test
    fun `the notice is due while the links need the person's consent, until it is shown`() {
        consentNeeded = false
        assertEquals(false, process { it.due() })
        consentNeeded = true
        assertEquals(true, process { it.due() })
        assertEquals(true, process { it.due() })
        process { it.shown() }
        assertEquals(false, process { it.due() })
        consentNeeded = false
        assertEquals(false, process { it.due() })
    }
}
