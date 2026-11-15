package io.github.kauri_off.kiroku.wiring

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

// The Android Keystore, which a JVM test does not have: AES keys kept in memory by alias, so a session can store tokens.
object KeysInMemory : Provider("AndroidKeyStore", 1.0, "Keys in memory, for tests") {
    private val stored = ConcurrentHashMap<String, SecretKey>()

    init {
        put("KeyStore.AndroidKeyStore", Store::class.java.name)
        put("KeyGenerator.AES", Generator::class.java.name)
    }

    fun install() {
        if (Security.getProvider(name) == null) Security.addProvider(this)
    }

    // Other tests of the same JVM find no Keystore again, and no key of this one.
    fun uninstall() {
        Security.removeProvider(name)
        stored.clear()
    }

    class Generator : KeyGeneratorSpi() {
        private var alias: String? = null

        override fun engineInit(
            params: AlgorithmParameterSpec?,
            random: SecureRandom?,
        ) {
            alias = (params as KeyGenParameterSpec).keystoreAlias
        }

        override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException()

        override fun engineInit(
            keysize: Int,
            random: SecureRandom?,
        ) = throw UnsupportedOperationException()

        override fun engineGenerateKey(): SecretKey {
            val key = SecretKeySpec(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes), "AES")
            stored[checkNotNull(alias)] = key
            return key
        }
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(
            alias: String,
            password: CharArray?,
        ): Key? = stored[alias]

        override fun engineLoad(
            stream: InputStream?,
            password: CharArray?,
        ) = Unit

        override fun engineContainsAlias(alias: String) = stored.containsKey(alias)

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(stored.keys)

        override fun engineSize() = stored.size

        override fun engineIsKeyEntry(alias: String) = stored.containsKey(alias)

        override fun engineDeleteEntry(alias: String) {
            stored.remove(alias)
        }

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        override fun engineGetCreationDate(alias: String): Date? = null

        override fun engineIsCertificateEntry(alias: String) = false

        override fun engineGetCertificateAlias(cert: Certificate?): String? = null

        override fun engineSetKeyEntry(
            alias: String,
            key: Key,
            password: CharArray?,
            chain: Array<out Certificate>?,
        ) = throw UnsupportedOperationException()

        override fun engineSetKeyEntry(
            alias: String,
            key: ByteArray?,
            chain: Array<out Certificate>?,
        ) = throw UnsupportedOperationException()

        override fun engineSetCertificateEntry(
            alias: String,
            cert: Certificate?,
        ) = throw UnsupportedOperationException()

        override fun engineStore(
            stream: OutputStream?,
            password: CharArray?,
        ) = throw UnsupportedOperationException()
    }

    private const val KEY_BYTES = 32
}
