package io.github.kauri_off.kiroku.data.session

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.util.UUID

// One per file and process: a second one on the same file fails at its first use.
internal class TokenStore(
    private val file: File,
    private val format: Serializer<Stored>,
    scope: CoroutineScope,
) {
    private val store = DataStoreFactory.create(serializer = format, scope = scope) { file }

    // A file that names no sign-in is signed out and is left until the next write; one that cannot be read at all throws.
    suspend fun read(): Stored =
        try {
            store.data.first()
        } catch (_: CorruptionException) {
            Stored.SignedOut
        }

    // The failure when the value could not be written; the file then holds what it held.
    suspend fun write(value: Stored): IOException? {
        var wasRead = false
        val update: suspend (Stored) -> Stored = {
            wasRead = true
            value
        }
        try {
            store.updateData(update)
            return null
        } catch (e: IOException) {
            if (wasRead) return e
        }
        return try {
            replace(value)
            null
        } catch (e: IOException) {
            e
        }
    }

    // What the file holds could not be read, so it stays until the new value is on the disk to take its place.
    private suspend fun replace(value: Stored) =
        withContext(Dispatchers.IO) {
            val next = File(file.path + NEXT)
            try {
                FileOutputStream(next).use { output ->
                    format.writeTo(value, output)
                    output.fd.sync()
                }
                // A directory in the file's place holds no session.
                if (file.isDirectory) file.delete()
                Files.move(next.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                next.delete()
            }
        }

    private companion object {
        const val NEXT = ".next"
    }
}

// Stored tokens that could not be read or did not open, as a report without the exception's message: its type and stack.
class TokensNotRead internal constructor(
    cause: Exception,
) : Exception(cause.javaClass.name) {
    init {
        stackTrace = cause.stackTrace
    }
}

// An empty file is signed out; else a marker and the sign-in's id in the clear, and after SEALED the sealed tokens.
internal class TokenFormat(
    private val cipher: TokenCipher,
    private val name: String,
    // Hears of tokens that did not open, without the exception's message.
    private val unexpected: (Throwable) -> Unit,
) : Serializer<Stored> {
    override val defaultValue: Stored = Stored.SignedOut

    // Tokens that do not open leave their sign-in expired for this process, and the file as it is.
    override suspend fun readFrom(input: InputStream): Stored {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return Stored.SignedOut
        val signIn = signInIn(bytes) ?: throw CorruptionException(UNREADABLE)
        return when {
            bytes[0] == EXPIRED && bytes.size == HEAD -> Stored.Expired(signIn)
            bytes[0] == SEALED -> unsealed(bytes)?.let(Stored::SignedIn) ?: Stored.Expired(signIn)
            else -> throw CorruptionException(UNREADABLE)
        }
    }

    // Only a well-formed id counts: a foreign file is not an expired session.
    private fun signInIn(bytes: ByteArray): String? {
        if (bytes.size < HEAD) return null
        val text = bytes.decodeToString(1, HEAD)
        return try {
            text.takeIf { UUID.fromString(it).toString() == it }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun unsealed(bytes: ByteArray): Tokens? =
        try {
            json.decodeFromString<Tokens>(cipher.open(bytes.copyOfRange(HEAD, bytes.size), bound(bytes)).decodeToString())
        } catch (e: GeneralSecurityException) {
            unopened(e)
        } catch (e: RuntimeException) {
            unopened(e)
        }

    private fun unopened(e: Exception): Tokens? {
        unexpected(TokensNotRead(e))
        return null
    }

    // The marker, the id and the file's name: tokens moved under another of them do not open.
    private fun bound(head: ByteArray): ByteArray = head.copyOf(HEAD) + name.encodeToByteArray()

    private fun head(
        marker: Byte,
        signIn: String,
    ): ByteArray = byteArrayOf(marker) + signIn.encodeToByteArray()

    override suspend fun writeTo(
        t: Stored,
        output: OutputStream,
    ) {
        when (t) {
            Stored.SignedOut -> Unit
            is Stored.Expired -> output.write(head(EXPIRED, t.signIn))
            is Stored.SignedIn -> output.write(sealed(t.tokens))
        }
    }

    private fun sealed(tokens: Tokens): ByteArray {
        val head = head(SEALED, tokens.signIn)
        return try {
            head + cipher.seal(json.encodeToString(tokens).encodeToByteArray(), bound(head))
        } catch (e: GeneralSecurityException) {
            throw IOException(UNSEALABLE, e)
        } catch (e: RuntimeException) {
            throw IOException(UNSEALABLE, e)
        }
    }

    private companion object {
        const val EXPIRED: Byte = 0
        const val SEALED: Byte = 1
        const val HEAD = 37
        const val UNREADABLE = "The token file cannot be read"
        const val UNSEALABLE = "The tokens cannot be sealed"
        val json = Json { ignoreUnknownKeys = true }
    }
}
