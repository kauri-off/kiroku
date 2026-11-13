package io.github.kauri_off.kiroku.data.session

import androidx.datastore.core.Serializer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.ProviderException

// S8, on the file alone; the Keystore's key is the device test's.
@RunWith(RobolectricTestRunner::class)
class TokenStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fixtures = mutableListOf<SessionFixture>()
    private val old = Stored.SignedIn(Tokens("sign-in", "old-access", "old-refresh", 100))
    private val new = Stored.SignedIn(Tokens("sign-in", "new-access", "new-refresh", 200))

    private fun fixture(directory: File = folder.newFolder()) = SessionFixture(directory).also { fixtures += it }

    @After
    fun close() = runBlocking { fixtures.forEach { it.close() } }

    private suspend fun SessionFixture.reread(format: Serializer<Stored> = TokenFormat(AesGcmCipher { key })): Stored {
        close()
        return store(format).read()
    }

    @Test
    fun whatWasWrittenIsReadByTheNextProcess() =
        runBlocking {
            val fixture = fixture()
            assertEquals("signed out", fixture.store().read().text())
            for (value in listOf(old, new, Stored.Expired("sign-in"), old, Stored.Expired("other"), Stored.SignedOut)) {
                assertTrue(fixture.apply { close() }.store().write(value))
                assertEquals(value.text(), fixture.reread().text())
            }
        }

    @Test
    fun theFileHoldsNoTokenInTheClearAndNoneAtAllOnceTheyAreGone() =
        runBlocking {
            val fixture = fixture()
            val store = fixture.store()
            store.write(old)
            val sealed = fixture.file.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(sealed.length > "old-access old-refresh".length)
            for (secret in listOf("old-access", "old-refresh")) assertFalse(secret in sealed)
            store.write(Stored.Expired("sign-in"))
            assertEquals("\u0000sign-in", fixture.file.readText())
            store.write(Stored.SignedOut)
            assertEquals(0L, fixture.file.length())
        }

    // Lets [after] bytes of a write through, then keeps the directory as a process dying there would leave it.
    private class CutFormat(
        private val whole: Serializer<Stored>,
        private val after: Int,
        private val cut: () -> Unit,
    ) : Serializer<Stored> by whole {
        override suspend fun writeTo(
            t: Stored,
            output: OutputStream,
        ) {
            val bytes = ByteArrayOutputStream().also { whole.writeTo(t, it) }.toByteArray()
            if (after > bytes.size) return output.write(bytes)
            output.write(bytes, 0, after)
            output.flush()
            cut()
            throw IOException("cut")
        }
    }

    @Test
    fun aWriteCutAtAnyPointLeavesTheOldOrTheNewPair() =
        runBlocking {
            val before = fixture()
            before.store().write(old)
            before.close()
            val key = before.key
            val format = TokenFormat(AesGcmCipher { key })

            var cuts = 0
            while (true) {
                val live = fixture()
                before.file.copyTo(live.file)
                var left: File? = null
                val cutting =
                    CutFormat(format, cuts) {
                        left = folder.newFolder().also { live.file.parentFile!!.copyRecursively(it) }
                    }
                val written = live.store(cutting).write(new)
                val directory = left
                if (directory == null) {
                    assertTrue(written)
                    assertEquals(new.text(), live.reread(format).text())
                    break
                }
                assertFalse(written)
                assertEquals("cut after $cuts bytes", old.text(), fixture(directory).store(format).read().text())
                assertEquals("after the failed write", old.text(), live.reread(format).text())
                cuts++
            }
            // Every length from nothing to the whole file, which is written but not yet in its place.
            assertEquals(before.file.length().toInt() + 1, cuts)
        }

    @Test
    fun aFileThatCannotBeReadIsSignedOutAndTheNextWriteReplacesIt() =
        runBlocking {
            val sealed = fixture().apply { store().write(old) }.file.readBytes()
            val unreadable =
                listOf(
                    byteArrayOf(1),
                    byteArrayOf(7, 7, 7),
                    byteArrayOf(0),
                    sealed.copyOf(sealed.size - 1),
                    sealed.copyOf(20),
                    sealed.clone().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() },
                    // Sealed with a key this store does not have.
                    sealed,
                )
            for (bytes in unreadable) {
                val fixture = fixture()
                fixture.file.writeBytes(bytes)
                val store = fixture.store()
                assertEquals("signed out", store.read().text())
                assertTrue(store.write(new))
                assertEquals(new.text(), fixture.reread().text())
            }
        }

    @Test
    fun aDirectoryInTheFilesPlaceIsSignedOut() =
        runBlocking {
            val fixture = fixture()
            fixture.file.mkdirs()
            assertEquals("signed out", fixture.store().read().text())
        }

    private class FailingCipher(
        private val real: TokenCipher,
        var opens: Boolean = true,
        var seals: Boolean = true,
    ) : TokenCipher {
        override fun seal(plain: ByteArray): ByteArray = if (seals) real.seal(plain) else throw ProviderException("no Keystore")

        override fun open(sealed: ByteArray): ByteArray = if (opens) real.open(sealed) else throw ProviderException("no Keystore")
    }

    @Test
    fun aReadThatFailedLeavesTheSessionForTheNextProcess() =
        runBlocking {
            val fixture = fixture()
            fixture.store().write(old)
            val failing = FailingCipher(AesGcmCipher { fixture.key }, opens = false)
            assertEquals("signed out", fixture.reread(TokenFormat(failing)).text())
            assertEquals(old.text(), fixture.reread().text())
        }

    @Test
    fun aWriteThatFailedSaysSoAndKeepsWhatWasThere() =
        runBlocking {
            val fixture = fixture()
            fixture.store().write(old)
            fixture.close()
            val store = fixture.store(TokenFormat(FailingCipher(AesGcmCipher { fixture.key }, seals = false)))
            assertFalse(store.write(new))
            assertEquals(old.text(), store.read().text())
            assertEquals(old.text(), fixture.reread().text())
        }

    @Test
    fun aSecondStoreOnTheSameFileIsRefused() {
        val fixture = fixture()
        runBlocking { fixture.store().read() }
        val second = fixture.store()
        assertThrows(IllegalStateException::class.java) { runBlocking { second.read() } }
    }
}
