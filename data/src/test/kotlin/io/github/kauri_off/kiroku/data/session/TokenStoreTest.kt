package io.github.kauri_off.kiroku.data.session

import androidx.datastore.core.Serializer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

// S8, on the file alone; the Keystore's key is the device test's.
@RunWith(RobolectricTestRunner::class)
class TokenStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fixtures = mutableListOf<SessionFixture>()
    private val old = Stored.SignedIn(Tokens(SIGN_IN, "old-access", "old-refresh", 100))
    private val new = Stored.SignedIn(Tokens(SIGN_IN, "new-access", "new-refresh", 200))

    private fun fixture(directory: File = folder.newFolder()) = SessionFixture(directory).also { fixtures += it }

    @After
    fun close() = runBlocking { fixtures.forEach { it.close() } }

    private suspend fun SessionFixture.reread(format: Serializer<Stored> = format()): Stored {
        close()
        return store(format).read()
    }

    @Test
    fun whatWasWrittenIsReadByTheNextProcess() =
        runBlocking {
            val fixture = fixture()
            assertEquals("signed out", fixture.store().read().text())
            for (value in listOf(old, new, Stored.Expired(SIGN_IN), old, Stored.Expired(OTHER_SIGN_IN), Stored.SignedOut)) {
                assertNull(fixture.apply { close() }.store().write(value))
                assertEquals(value.text(), fixture.reread().text())
            }
        }

    @Test
    fun theFileHoldsTheSignInAndNoTokenInTheClearAndNoneAtAllOnceTheyAreGone() =
        runBlocking {
            val fixture = fixture()
            val store = fixture.store()
            store.write(old)
            val sealed = fixture.file.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(sealed.startsWith("\u0001$SIGN_IN"))
            assertTrue(sealed.length > "\u0001$SIGN_IN old-access old-refresh".length)
            for (secret in listOf("old-access", "old-refresh", "access", "refresh")) assertFalse(secret in sealed)
            store.write(Stored.Expired(SIGN_IN))
            assertEquals("\u0000$SIGN_IN", fixture.file.readText())
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
            val format = before.format()

            var cuts = 0
            while (true) {
                val live = fixture()
                before.file.copyTo(live.file)
                var left: File? = null
                val cutting =
                    CutFormat(format, cuts) {
                        left = folder.newFolder().also { live.file.parentFile!!.copyRecursively(it) }
                    }
                val failure = live.store(cutting).write(new)
                val directory = left
                if (directory == null) {
                    assertNull(failure)
                    assertEquals(new.text(), live.reread(format).text())
                    break
                }
                assertNotNull(failure)
                assertEquals("cut after $cuts bytes", old.text(), fixture(directory).store(format).read().text())
                assertEquals("after the failed write", old.text(), live.reread(format).text())
                cuts++
            }
            // Every length from nothing to the whole file, which is written but not yet in its place.
            assertEquals(before.file.length().toInt() + 1, cuts)
        }

    @Test
    fun aFileThatNamesNoSignInIsSignedOutAndTheNextWriteReplacesIt() =
        runBlocking {
            val sealed = fixture().apply { store().write(old) }.file.readBytes()
            val id = SIGN_IN.encodeToByteArray()
            val nameless =
                listOf(
                    byteArrayOf(1),
                    byteArrayOf(7, 7, 7),
                    byteArrayOf(0),
                    sealed.copyOf(20),
                    sealed.clone().also { it[5] = 'x'.code.toByte() },
                    byteArrayOf(7) + sealed.drop(1),
                    byteArrayOf(0) + "PNG\u0000 and what else a foreign file has".encodeToByteArray().copyOf(36),
                    byteArrayOf(0) + SIGN_IN.uppercase().encodeToByteArray(),
                    byteArrayOf(0) + id + id,
                    byteArrayOf(0) + id.copyOf(35),
                )
            for ((index, bytes) in nameless.withIndex()) {
                val fixture = fixture()
                fixture.file.writeBytes(bytes)
                val store = fixture.store()
                assertEquals("$index", "signed out", store.read().text())
                assertEquals("$index", bytes.toList(), fixture.file.readBytes().toList())
                assertNull(store.write(new))
                assertEquals(new.text(), fixture.reread().text())
            }
        }

    @Test
    fun aFileThatCannotBeReadAtAllIsNotSignedOutAndAWriteReplacesIt() =
        runBlocking {
            val fixture = fixture()
            fixture.file.mkdirs()
            val store = fixture.store()
            assertThrows(IOException::class.java) { runBlocking { store.read() } }
            assertTrue(fixture.file.isDirectory)
            assertNull(store.write(new))
            assertEquals(new.text(), fixture.reread().text())
        }

    @Test
    fun aWriteThatFailsOverAFileThatCannotBeReadLeavesThatFile() =
        runBlocking {
            val fixture = fixture()
            fixture.store().write(old)
            val file = fixture.file.readBytes().toList()
            fixture.close()
            val disk = FailingDisk(fixture.format(), fullToo = true)
            val store = fixture.store(disk)
            assertNotNull(store.write(new))
            assertEquals(file, fixture.file.readBytes().toList())
            assertEquals(
                listOf(fixture.file.name),
                fixture.file.parentFile!!
                    .list()
                    .orEmpty()
                    .toList(),
            )

            disk.fullToo = false
            assertNull(store.write(new))
            assertEquals(new.text(), fixture.reread().text())
        }

    @Test
    fun tokensThatDoNotOpenLeaveTheirSignInExpiredAndTheFileAsItIs() =
        runBlocking {
            val source = fixture()
            source.store().write(old)
            val sealed = source.file.readBytes()
            val other = fixture()
            val closed = FailingCipher(source.cipher, opens = false)
            // What the file holds, and the format that reads it: the first four with the key that sealed it.
            val unopened =
                listOf(
                    sealed.copyOf(sealed.size - 1) to source.format(),
                    sealed.clone().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() } to source.format(),
                    sealed.copyOf(37) to source.format(),
                    sealed to source.format(name = "another"),
                    sealed to other.format(),
                    sealed to source.format(closed),
                )
            for ((index, case) in unopened.withIndex()) {
                val (bytes, format) = case
                closed.opens = false
                val fixture = fixture()
                fixture.file.writeBytes(bytes)
                val store = fixture.store(format)
                assertEquals("$index", "expired $SIGN_IN", store.read().text())
                assertEquals("$index", bytes.toList(), fixture.file.readBytes().toList())
                closed.opens = true
                assertNull(store.write(new))
                assertEquals("$index", new.text(), fixture.reread(format).text())
            }
        }

    @Test
    fun aStartThatCannotOpenTheTokensLeavesThemForTheNext() =
        runBlocking {
            val fixture = fixture()
            fixture.store().write(old)
            val failing = FailingCipher(fixture.cipher, opens = false)
            assertEquals("expired $SIGN_IN", fixture.reread(fixture.format(failing)).text())
            assertEquals(old.text(), fixture.reread().text())
        }

    @Test
    fun aWriteThatFailedSaysSoAndKeepsWhatWasThere() =
        runBlocking {
            val fixture = fixture()
            fixture.store().write(old)
            fixture.close()
            val store = fixture.store(fixture.format(FailingCipher(fixture.cipher, seals = false)))
            assertNotNull(store.write(new))
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
