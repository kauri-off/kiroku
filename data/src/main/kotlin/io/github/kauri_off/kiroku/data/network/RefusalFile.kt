package io.github.kauri_off.kiroku.data.network

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// A source's refusal kept in a file of its own, so that a new process sends nothing while it holds; used off the main thread.
internal class RefusalFile(
    private val file: File,
    private val clock: Clock,
    // No hold and no wait is written longer than this, so a file that holds one is not read.
    private val longest: Duration,
    private val report: (Throwable) -> Unit,
    // What is reported of a file that cannot be read or deleted.
    private val odd: () -> Throwable,
) {
    // What is left of a hold, and the wait a next refusal doubles.
    class Held(
        val remaining: Duration,
        val wait: Duration,
    )

    // In milliseconds, as the file holds them; [restartedAt] is when the clock was found behind [setAt].
    private data class Kept(
        val setAt: Long,
        val held: Long,
        val wait: Long,
        val restartedAt: Long? = null,
    )

    private var read = false
    private var kept: Kept? = null

    // What is left of the hold as it was set or, while the clock is behind the refusal, as counted from when that was found.
    @Synchronized
    fun read(): Held? {
        val kept = kept() ?: return null
        val now = clock.millis()
        val passed = now - if (now >= kept.setAt) kept.setAt else restarted(kept, now)
        return Held((kept.held - passed).coerceAtLeast(0).milliseconds, kept.wait.milliseconds)
    }

    private fun restarted(
        kept: Kept,
        now: Long,
    ): Long = kept.restartedAt?.takeIf { it <= now } ?: now.also { keep(kept.copy(restartedAt = now)) }

    // A hold of [held] from now on.
    @Synchronized
    fun write(
        held: Duration,
        wait: Duration,
    ) = keep(Kept(clock.millis(), held.inWholeMilliseconds, wait.inWholeMilliseconds))

    @Synchronized
    fun clear() {
        if (kept() == null) return
        kept = null
        if (file.exists() && !file.delete()) report(odd())
    }

    private fun kept(): Kept? {
        if (!read) kept = stored()
        read = true
        return kept
    }

    private fun stored(): Kept? {
        if (!file.exists()) return null
        val values =
            try {
                file
                    .readText()
                    .trim()
                    .split(' ')
                    .map(String::toLong)
            } catch (_: IOException) {
                return unreadable()
            } catch (_: NumberFormatException) {
                return unreadable()
            }
        val bound = longest.inWholeMilliseconds
        if (values.size !in 3..4 || values.any { it < 0 } || values[1] > bound || values[2] > bound) return unreadable()
        return Kept(values[0], values[1], values[2], values.getOrNull(3))
    }

    // Reported once: a file that cannot be read is deleted, as if no refusal had been kept.
    private fun unreadable(): Kept? {
        report(odd())
        file.delete()
        return null
    }

    // A value the file does not take still holds in this process.
    private fun keep(value: Kept) {
        kept = value
        read = true
        val next = File(file.path + ".new")
        try {
            file.parentFile?.mkdirs()
            next.writeText(listOfNotNull(value.setAt, value.held, value.wait, value.restartedAt).joinToString(" ", postfix = "\n"))
            Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            next.delete()
            report(UnusableAnswer(e))
        }
    }
}
