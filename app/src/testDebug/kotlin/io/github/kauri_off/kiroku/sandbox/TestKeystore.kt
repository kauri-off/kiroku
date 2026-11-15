package io.github.kauri_off.kiroku.sandbox

import android.security.keystore.KeyGenParameterSpec
import org.junit.rules.ExternalResource
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

// The Android Keystore, which Robolectric has not, while a test runs: AES keys by alias, kept until it ends.
class TestKeystore : ExternalResource() {
    override fun before() {
        check(Security.getProvider(NAME) == null) { "A Keystore is installed already" }
        Security.addProvider(Keystore())
    }

    override fun after() {
        Security.removeProvider(NAME)
        keys.clear()
    }

    private class Keystore : Provider(NAME, 1.0, "A test's Android Keystore") {
        init {
            put("KeyStore.$NAME", Store::class.java.name)
            put("KeyGenerator.AES", Generator::class.java.name)
        }
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(
            alias: String,
            password: CharArray?,
        ): Key? = keys[alias]

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        override fun engineGetCreationDate(alias: String): Date? = null

        override fun engineSetKeyEntry(
            alias: String,
            key: Key,
            password: CharArray?,
            chain: Array<out Certificate>?,
        ) {
            keys[alias] = key as SecretKey
        }

        override fun engineSetKeyEntry(
            alias: String,
            key: ByteArray,
            chain: Array<out Certificate>?,
        ) = throw UnsupportedOperationException()

        override fun engineSetCertificateEntry(
            alias: String,
            cert: Certificate,
        ) = throw UnsupportedOperationException()

        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())

        override fun engineContainsAlias(alias: String) = keys.containsKey(alias)

        override fun engineSize() = keys.size

        override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)

        override fun engineIsCertificateEntry(alias: String) = false

        override fun engineGetCertificateAlias(cert: Certificate): String? = null

        override fun engineStore(
            stream: OutputStream?,
            password: CharArray?,
        ) = Unit

        override fun engineLoad(
            stream: InputStream?,
            password: CharArray?,
        ) = Unit
    }

    class Generator : KeyGeneratorSpi() {
        private var alias: String? = null

        override fun engineInit(random: SecureRandom?) = Unit

        override fun engineInit(
            params: AlgorithmParameterSpec,
            random: SecureRandom?,
        ) {
            alias = (params as KeyGenParameterSpec).keystoreAlias
        }

        override fun engineInit(
            keysize: Int,
            random: SecureRandom?,
        ) = Unit

        override fun engineGenerateKey(): SecretKey {
            val key = SecretKeySpec(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes), "AES")
            alias?.let { keys[it] = key }
            return key
        }
    }

    private companion object {
        const val NAME = "AndroidKeyStore"
        const val KEY_BYTES = 32
        val keys = ConcurrentHashMap<String, SecretKey>()
    }
}
