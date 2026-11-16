package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.data.network.UnusableAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// AniList's refusal kept in a file of its own, so that a new process sends nothing while it holds.
internal class AniListBlock(
    private val file: File,
    private val clock: Clock,
    private val report: (Throwable) -> Unit,
) {
    // What is left of a hold, and the wait a next refusal doubles.
    class Held(
        val remaining: Duration,
        val wait: Duration,
    )

    // Never more than the hold that was set, so a clock set back cannot lengthen it; a clock set forward can end it.
    suspend fun read(): Held? =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            val values =
                try {
                    file
                        .readText()
                        .trim()
                        .split(' ')
                        .map(String::toLong)
                } catch (_: IOException) {
                    report(AniListOddAnswer("block"))
                    return@withContext null
                } catch (_: NumberFormatException) {
                    report(AniListOddAnswer("block"))
                    return@withContext null
                }
            if (values.size != 3 || values.any { it < 0 }) {
                report(AniListOddAnswer("block"))
                return@withContext null
            }
            val (setAt, held, wait) = values
            val passed = clock.millis() - setAt
            Held((held - passed).coerceIn(0, held).milliseconds, wait.milliseconds)
        }

    suspend fun write(
        at: Instant,
        held: Duration,
        wait: Duration,
    ) = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            val next = File(file.path + ".new")
            next.writeText("${at.toEpochMilli()} ${held.inWholeMilliseconds} ${wait.inWholeMilliseconds}\n")
            Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            report(UnusableAnswer(e))
        }
    }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            if (file.exists() && !file.delete()) report(AniListOddAnswer("block"))
        }
}
