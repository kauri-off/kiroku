package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.data.session.fileRetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.time.toKotlinDuration

internal const val MANGA_CAPTURE_COMPLETE = "manga_mutation_complete"

internal class MangaRecordingFiles(
    directory: () -> File,
    private val scope: CoroutineScope,
) {
    private val root by lazy(directory)
    private var resident: SessionOwner? = null
    private var retiring = false
    private var retrying = false

    @Synchronized
    fun start(owner: SessionOwner): File {
        if (resident != null && resident !== owner) forget()
        if (retiring) throw IOException()
        val parent = Files.createDirectories(root.toPath())
        return Files.createDirectory(parent.resolve("run-${UUID.randomUUID()}")).toFile().also { resident = owner }
    }

    @Synchronized
    fun complete(pending: File): File {
        val destination = File(root, pending.name.replaceFirst("run-", "capture-"))
        Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        Files.createFile(destination.toPath().resolve(MANGA_CAPTURE_COMPLETE))
        syncedDirectory(root)
        return destination
    }

    @Synchronized
    fun forgetRetired(
        current: (SessionOwner) -> Boolean,
        forgotten: () -> Unit,
    ) {
        if (resident?.let(current) == false) forget(forgotten)
    }

    @Synchronized
    fun report(
        owner: SessionOwner,
        directory: File?,
        show: (File?) -> Unit,
    ) {
        show(directory?.takeIf { resident === owner && it.isDirectory })
    }

    @Synchronized
    fun forget(forgotten: () -> Unit = {}) {
        resident = null
        retiring = !erase()
        forgotten()
        if (!retiring || retrying) return
        retrying = true
        scope.launch {
            var attempt = 0
            do {
                delay(fileRetry(attempt++).toKotlinDuration())
            } while (!retryRetirement())
        }
    }

    @Synchronized
    private fun retryRetirement(): Boolean {
        if (retiring) retiring = !erase()
        if (!retiring) retrying = false
        return !retiring
    }

    private fun erase(): Boolean =
        try {
            root.deleteRecursively()
        } catch (_: SecurityException) {
            false
        }
}
