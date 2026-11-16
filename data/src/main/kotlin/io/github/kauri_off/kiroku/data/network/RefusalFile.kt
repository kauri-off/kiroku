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
    private val report: (Throwable) -> Unit,
    // What is reported of a file that cannot be read or deleted.
    private val odd: () -> Throwable,
) {
    // What is left of a hold, and the wait a next refusal doubles.
    class Held(
        val remaining: Duration,
        val wait: Duration,
    )

    // Never more than the hold that was set: a clock found set back starts the hold again, for the next process too.
    fun read(): Held? {
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
        if (values.size != 3 || values.any { it < 0 }) return unreadable()
        val (setAt, held, wait) = values
        val passed = clock.millis() - setAt
        if (passed < 0) write(held.milliseconds, wait.milliseconds)
        return Held((held - passed).coerceIn(0, held).milliseconds, wait.milliseconds)
    }

    // Reported once: a file that cannot be read is deleted, as if no refusal had been kept.
    private fun unreadable(): Held? {
        report(odd())
        file.delete()
        return null
    }

    // A hold of [held] from now on.
    fun write(
        held: Duration,
        wait: Duration,
    ) {
        val next = File(file.path + ".new")
        try {
            file.parentFile?.mkdirs()
            next.writeText("${clock.millis()} ${held.inWholeMilliseconds} ${wait.inWholeMilliseconds}\n")
            Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            next.delete()
            report(UnusableAnswer(e))
        }
    }

    fun clear() {
        if (file.exists() && !file.delete()) report(odd())
    }
}
