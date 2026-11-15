package io.github.kauri_off.kiroku.data.sandbox

import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

// The pairs of tokens the sandbox granted and has not revoked, kept in [file] so that a sign-in outlives the process.
class SandboxTokens(
    private val file: File? = null,
) {
    private val random = SecureRandom()
    private val pairs: MutableMap<String, String> by lazy { read().toMutableMap() }

    fun accepts(access: String): Boolean = synchronized(pairs) { access in pairs }

    fun refreshes(refresh: String): Boolean = synchronized(pairs) { refresh in pairs.values }

    // A new pair, which replaces [refresh]'s when it is given: MAL revokes the old pair at a refresh.
    fun granted(refresh: String? = null): Pair<String, String> =
        synchronized(pairs) {
            if (refresh != null) pairs.values.remove(refresh)
            val pair = text(ACCESS) to text(REFRESH)
            pairs += pair
            kept()
            pair
        }

    private fun text(prefix: String): String =
        prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(BYTES).also(random::nextBytes))

    private fun kept() {
        val file = file ?: return
        val next = File(file.path + ".next")
        next.writeText(pairs.entries.joinToString("\n") { "${it.key} ${it.value}" })
        check(next.renameTo(file)) { "The sandbox's tokens were not kept" }
    }

    private fun read(): Map<String, String> =
        try {
            file
                ?.takeIf { it.isFile }
                ?.readLines()
                ?.mapNotNull { line -> line.split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                ?.toMap()
                .orEmpty()
        } catch (_: IOException) {
            emptyMap()
        }

    private companion object {
        const val ACCESS = "sandbox-access-"
        const val REFRESH = "sandbox-refresh-"
        const val BYTES = 24
    }
}
