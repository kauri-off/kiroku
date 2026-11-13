package io.github.kauri_off.kiroku.data.session

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

// One per file and process: a second one on the same file fails at its first use.
internal class TokenStore(
    private val file: File,
    format: Serializer<Stored>,
    scope: CoroutineScope,
) {
    private val store = DataStoreFactory.create(serializer = format, scope = scope) { file }

    // A file that cannot be read is signed out, and is left as it is until the next write.
    suspend fun read(): Stored =
        try {
            store.data.first()
        } catch (_: IOException) {
            Stored.SignedOut
        }

    // False when the value could not be written; the file then holds what it held.
    suspend fun write(value: Stored): Boolean {
        var wasRead = false
        val update: suspend (Stored) -> Stored = {
            wasRead = true
            value
        }
        try {
            store.updateData(update)
            return true
        } catch (_: IOException) {
            if (wasRead) return false
        }
        // What the file held could not be read, so nothing is lost by replacing it.
        file.delete()
        return try {
            store.updateData(update)
            true
        } catch (_: IOException) {
            false
        }
    }
}

// An empty file is signed out; EXPIRED comes before the expired sign-in's id, SEALED before the sealed tokens.
internal class TokenFormat(
    private val cipher: TokenCipher,
) : Serializer<Stored> {
    override val defaultValue: Stored = Stored.SignedOut

    override suspend fun readFrom(input: InputStream): Stored {
        val bytes = input.readBytes()
        return when {
            bytes.isEmpty() -> Stored.SignedOut
            bytes.size > 1 && bytes[0] == EXPIRED -> Stored.Expired(bytes.decodeToString(startIndex = 1))
            else -> Stored.SignedIn(unsealed(bytes))
        }
    }

    // The cause is left out: a parser's message quotes its input.
    private fun unsealed(bytes: ByteArray): Tokens =
        try {
            if (bytes[0] != SEALED) throw CorruptionException(UNREADABLE)
            json.decodeFromString<Tokens>(cipher.open(bytes.copyOfRange(1, bytes.size)).decodeToString())
        } catch (_: GeneralSecurityException) {
            throw CorruptionException(UNREADABLE)
        } catch (_: RuntimeException) {
            throw CorruptionException(UNREADABLE)
        }

    override suspend fun writeTo(
        t: Stored,
        output: OutputStream,
    ) {
        when (t) {
            Stored.SignedOut -> Unit
            is Stored.Expired -> output.write(byteArrayOf(EXPIRED) + t.signIn.encodeToByteArray())
            is Stored.SignedIn -> output.write(byteArrayOf(SEALED) + sealed(t.tokens))
        }
    }

    private fun sealed(tokens: Tokens): ByteArray =
        try {
            cipher.seal(json.encodeToString(tokens).encodeToByteArray())
        } catch (e: GeneralSecurityException) {
            throw IOException(UNSEALABLE, e)
        } catch (e: RuntimeException) {
            throw IOException(UNSEALABLE, e)
        }

    private companion object {
        const val EXPIRED: Byte = 0
        const val SEALED: Byte = 1
        const val UNREADABLE = "The token file cannot be read"
        const val UNSEALABLE = "The tokens cannot be sealed"
        val json = Json { ignoreUnknownKeys = true }
    }
}
