package io.github.kauri_off.kiroku.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec

// The cipher over keys as a Keystore gives them; the Keystore itself is the device test's.
class TokenCipherTest {
    private val keys = TestKeys()
    private val cipher = AesGcmCipher(keys)
    private val plain = "the tokens".encodeToByteArray()
    private val bound = "a file".encodeToByteArray()

    @Test
    fun whatWasSealedOpensAndIsNeverSealedTheSameWayTwice() {
        val sealed = cipher.seal(plain, bound)
        assertEquals(plain.toList(), cipher.open(sealed, bound).toList())
        assertFalse(sealed.toList() == cipher.seal(plain, bound).toList())
        assertEquals(0, keys.made)
    }

    @Test
    fun whatWasSealedDoesNotOpenUnderAnotherBoundOrKeyOrWhenChanged() {
        val sealed = cipher.seal(plain, bound)
        assertThrows(GeneralSecurityException::class.java) { cipher.open(sealed, "another file".encodeToByteArray()) }
        for (at in listOf(0, 12, sealed.size - 1)) {
            val changed = sealed.clone().also { it[at] = (it[at] + 1).toByte() }
            assertThrows("$at", GeneralSecurityException::class.java) { cipher.open(changed, bound) }
        }
        assertThrows(GeneralSecurityException::class.java) { cipher.open(sealed.copyOf(11), bound) }
        assertThrows(GeneralSecurityException::class.java) { AesGcmCipher(TestKeys()).open(sealed, bound) }
    }

    @Test
    fun aKeyIsMadeOnlyToSealAndOnlyWhenThereIsNone() {
        keys.key = null
        assertThrows(GeneralSecurityException::class.java) { cipher.open(ByteArray(40), bound) }
        assertNull(keys.key)
        assertEquals(0, keys.made)

        val sealed = cipher.seal(plain, bound)
        assertEquals(1, keys.made)
        assertEquals(plain.toList(), cipher.open(cipher.seal(plain, bound), bound).toList())
        assertEquals(plain.toList(), cipher.open(sealed, bound).toList())
        assertEquals(1, keys.made)
    }

    @Test
    fun aKeyThatSealsNothingIsReplacedOnce() {
        keys.key = SecretKeySpec(ByteArray(5), "AES")
        val sealed = cipher.seal(plain, bound)
        assertEquals(1, keys.made)
        assertEquals(plain.toList(), cipher.open(sealed, bound).toList())
    }
}
