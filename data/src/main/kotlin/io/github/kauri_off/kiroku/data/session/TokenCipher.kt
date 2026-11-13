package io.github.kauri_off.kiroku.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface TokenCipher {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

// AES-GCM with the nonce the cipher chose in front of what it sealed: the Keystore refuses a caller's nonce.
internal class AesGcmCipher(
    private val key: () -> SecretKey,
) : TokenCipher {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val nonce = cipher.iv
        if (nonce.size != NONCE_BYTES) throw GeneralSecurityException("Unexpected nonce length")
        return nonce + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < NONCE_BYTES) throw GeneralSecurityException("Too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

// The key never leaves the Keystore and asks for no unlock, so background work can use it.
internal fun keystoreKey(alias: String): SecretKey {
    val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keystore.getKey(alias, null) as SecretKey?)?.let { return it }
    val spec =
        KeyGenParameterSpec
            .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
}

private const val KEY_BITS = 256
