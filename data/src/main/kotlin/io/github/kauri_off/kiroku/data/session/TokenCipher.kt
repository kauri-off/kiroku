package io.github.kauri_off.kiroku.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// What was sealed opens only with the [bound] it was sealed with, which is not part of it.
internal interface TokenCipher {
    fun seal(
        plain: ByteArray,
        bound: ByteArray,
    ): ByteArray

    fun open(
        sealed: ByteArray,
        bound: ByteArray,
    ): ByteArray
}

internal interface KeySource {
    fun existing(): SecretKey?

    // Takes the place of the key there may be.
    fun fresh(): SecretKey
}

private val keyLock = Any()

// AES-GCM with the nonce the cipher chose in front of what it sealed: the Keystore refuses a caller's nonce.
internal class AesGcmCipher(
    private val keys: KeySource,
) : TokenCipher {
    // A key that seals nothing is replaced, so that a new sign-in can always be stored.
    override fun seal(
        plain: ByteArray,
        bound: ByteArray,
    ): ByteArray =
        synchronized(keyLock) {
            val withExisting =
                try {
                    keys.existing()?.let { sealed(it, plain, bound) }
                } catch (_: GeneralSecurityException) {
                    null
                } catch (_: ProviderException) {
                    null
                }
            withExisting ?: sealed(keys.fresh(), plain, bound)
        }

    private fun sealed(
        key: SecretKey,
        plain: ByteArray,
        bound: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv
        if (nonce.size != NONCE_BYTES) throw GeneralSecurityException("Unexpected nonce length")
        cipher.updateAAD(bound)
        return nonce + cipher.doFinal(plain)
    }

    override fun open(
        sealed: ByteArray,
        bound: ByteArray,
    ): ByteArray {
        if (sealed.size < NONCE_BYTES) throw GeneralSecurityException("Too short")
        val key = keys.existing() ?: throw GeneralSecurityException("No key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
        cipher.updateAAD(bound)
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}

// A key that never leaves the Keystore and asks for no unlock, so background work can use it.
internal class KeystoreKeys(
    private val alias: String,
) : KeySource {
    override fun existing(): SecretKey? = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(alias, null) as? SecretKey

    override fun fresh(): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_BITS = 256
    }
}
