package io.github.kauri_off.kiroku.data.signin

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// A sign-in that waits in the browser: what its redirect must bring back, its code's verifier and when it started.
internal class PendingSignIn(
    val state: String,
    val verifier: String,
    val startedAt: Long,
)

// S2: the waiting sign-in is a file, so the process that takes the redirect need not be the one that started it.
internal class PendingStore(
    private val file: File,
    scope: CoroutineScope,
) {
    private val store = DataStoreFactory.create(serializer = PendingFormat, scope = scope) { file }

    suspend fun read(): PendingSignIn? =
        try {
            store.data.first().orNull()
        } catch (_: IOException) {
            null
        }

    // False when the value could not be written; a file that cannot even be read is replaced.
    suspend fun write(value: PendingSignIn?): Boolean {
        val update: suspend (PendingSignIn) -> PendingSignIn = { value ?: Absent }
        try {
            store.updateData(update)
            return true
        } catch (_: IOException) {
            file.delete()
        }
        return try {
            store.updateData(update)
            true
        } catch (_: IOException) {
            false
        }
    }
}

private val Absent = PendingSignIn("", "", 0)

private fun PendingSignIn.orNull(): PendingSignIn? = takeIf { it !== Absent }

// Three lines; an empty file or any other content is no sign-in.
private object PendingFormat : Serializer<PendingSignIn> {
    override val defaultValue = Absent

    override suspend fun readFrom(input: InputStream): PendingSignIn {
        val lines = input.readBytes().decodeToString().split('\n')
        val startedAt = lines.getOrNull(2)?.toLongOrNull()
        if (lines.size != 3 || startedAt == null || lines[0].isEmpty() || lines[1].isEmpty()) return Absent
        return PendingSignIn(lines[0], lines[1], startedAt)
    }

    override suspend fun writeTo(
        t: PendingSignIn,
        output: OutputStream,
    ) {
        if (t !== Absent) output.write("${t.state}\n${t.verifier}\n${t.startedAt}".encodeToByteArray())
    }
}
