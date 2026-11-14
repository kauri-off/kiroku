package io.github.kauri_off.kiroku.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

// S8 on a device: the session as the app builds it, over the real Keystore.
class KeystoreSessionTest {
    private val file = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "keystore-test/session")
    private val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private var scope: CoroutineScope? = null
    private val reported = CopyOnWriteArrayList<Throwable>()

    private suspend fun open(): MalSession {
        scope?.coroutineContext?.job?.cancelAndJoin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        return malSession(file, { error("No token request was expected") }, Clock.systemUTC(), scope) { reported += it }
    }

    @Before
    @After
    fun clear() {
        runBlocking { scope?.coroutineContext?.job?.cancelAndJoin() }
        file.parentFile!!.deleteRecursively()
        keystore.deleteEntry(KEY_ALIAS)
    }

    private suspend fun MalSession.sent(): List<String> {
        val sent = mutableListOf<String>()
        authorised { token ->
            sent += token
            Attempt.Answered(Outcome.Success(Unit))
        }
        return sent
    }

    @Test
    fun theTokensAreSealedWithAKeyThatStaysInTheKeystoreAndTheNextProcessReadsThem() =
        runBlocking {
            val session = open()
            assertEquals(Session.SignedOut, session.observeSession().first())
            assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("device-access-token", "device-refresh-token")))
            val signedIn = session.observeSession().first()
            assertTrue(signedIn is Session.SignedIn)

            val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(bytes.length > "device-access-token device-refresh-token".length)
            for (secret in listOf("device-access-token", "device-refresh-token", "device", "token")) assertFalse(secret in bytes)

            val key = keystore.getKey(KEY_ALIAS, null) as SecretKey
            assertNull(key.encoded)
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
            assertFalse(info.isUserAuthenticationRequired)
            assertEquals(256, info.keySize)

            val next = open()
            assertEquals(signedIn, next.observeSession().first())
            assertEquals(listOf("device-access-token"), next.sent())
            assertEquals(emptyList<Throwable>(), reported)
        }

    @Test
    fun aSecondSignInIsSealedWithTheSameKeyAndReplacesTheFirst() =
        runBlocking {
            val session = open()
            session.signIn(TokenGrant("first-access", "first-refresh"))
            val first = file.readBytes().toList()
            assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("second-access", "second-refresh")))
            assertFalse(first == file.readBytes().toList())
            assertEquals(listOf("second-access"), open().sent())
            assertEquals(emptyList<Throwable>(), reported)
        }

    @Test
    fun withoutItsKeyTheSignInIsExpiredAndANewSignInWorks() =
        runBlocking {
            val first = open()
            first.signIn(TokenGrant("device-access-token", "device-refresh-token"))
            val id = (first.observeSession().first() as Session.SignedIn).signIn
            open()
            keystore.deleteEntry(KEY_ALIAS)
            val session = open()
            assertEquals(Session.Expired(id), session.observeSession().first())
            assertEquals(emptyList<String>(), session.sent())
            // The tokens that did not open are reported once, by the type of what the cipher threw.
            assertEquals(listOf("java.security.GeneralSecurityException"), reported.map { it.message })
            assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("new-access", "new-refresh")))
            assertEquals(listOf("new-access"), open().sent())
        }

    @Test
    fun aKeyThatSealsNothingIsReplaced() =
        runBlocking {
            val unfit =
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(unfit) }.generateKey()
            assertEquals(Outcome.Success(Unit), open().signIn(TokenGrant("device-access-token", "device-refresh-token")))
            assertEquals(listOf("device-access-token"), open().sent())
            assertEquals(emptyList<Throwable>(), reported)
        }
}
