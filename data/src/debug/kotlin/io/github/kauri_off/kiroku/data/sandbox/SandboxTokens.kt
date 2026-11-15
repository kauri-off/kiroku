package io.github.kauri_off.kiroku.data.sandbox

import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

// The pairs of tokens the sandbox granted and has not revoked, each valid until its time on [clock], kept in [file].
class SandboxTokens(
    private val clock: Clock,
    private val file: File? = null,
) {
    private class Grant(
        val access: String,
        val refresh: String,
        val accessUntil: Instant,
        val refreshUntil: Instant,
    )

    private val random = SecureRandom()
    private val grants: MutableList<Grant> by lazy { read().toMutableList() }

    fun accepts(access: String): Boolean = synchronized(grants) { grants.any { it.access == access && now() < it.accessUntil } }

    fun refreshes(refresh: String): Boolean = synchronized(grants) { grants.any { it.refresh == refresh && now() < it.refreshUntil } }

    // A new pair, which replaces [refresh]'s when it is given: MAL revokes the old pair at a refresh.
    fun granted(refresh: String? = null): Pair<String, String> =
        synchronized(grants) {
            if (refresh != null) grants.removeAll { it.refresh == refresh }
            val now = now()
            val grant = Grant(text(ACCESS), text(REFRESH), now + ACCESS_LIFETIME, now + REFRESH_LIFETIME)
            grants += grant
            kept()
            grant.access to grant.refresh
        }

    // Every access token expires now; the refresh tokens still renew them.
    fun expireAccess() =
        synchronized(grants) {
            val now = now()
            grants.replaceAll { Grant(it.access, it.refresh, minOf(it.accessUntil, now), it.refreshUntil) }
            kept()
        }

    private fun now(): Instant = clock.instant()

    private fun text(prefix: String): String =
        prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(BYTES).also(random::nextBytes))

    private fun kept() {
        val file = file ?: return
        val next = File(file.path + ".next")
        next.writeText(
            grants.joinToString("\n") { "${it.access} ${it.refresh} ${it.accessUntil.epochSecond} ${it.refreshUntil.epochSecond}" },
        )
        check(next.renameTo(file)) { "The sandbox's tokens were not kept" }
    }

    private fun read(): List<Grant> =
        try {
            file
                ?.takeIf { it.isFile }
                ?.readLines()
                ?.mapNotNull { line ->
                    val parts = line.split(' ').takeIf { it.size == FIELDS } ?: return@mapNotNull null
                    val access = parts[2].toLongOrNull() ?: return@mapNotNull null
                    val refresh = parts[3].toLongOrNull() ?: return@mapNotNull null
                    Grant(parts[0], parts[1], Instant.ofEpochSecond(access), Instant.ofEpochSecond(refresh))
                }.orEmpty()
        } catch (_: IOException) {
            emptyList()
        }

    companion object {
        // The access token's lifetime in MAL's printed token answer; the owner's recording gives one no shorter.
        val ACCESS_LIFETIME: Duration = Duration.ofSeconds(2_415_600)

        // The month the refresh token lives (section 3.1), as its shortest month.
        val REFRESH_LIFETIME: Duration = Duration.ofDays(28)

        private const val ACCESS = "sandbox-access-"
        private const val REFRESH = "sandbox-refresh-"
        private const val BYTES = 24
        private const val FIELDS = 4
    }
}
