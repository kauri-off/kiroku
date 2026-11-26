package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.repository.MangaListEditResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MangaListQueue {
    val lock = Mutex()

    private class Batch {
        var edits = 0
        var failed = false
    }

    private val batches = mutableMapOf<MangaId, Batch>()

    suspend fun edit(
        id: MangaId,
        save: suspend () -> MangaListEditResult,
    ): MangaListEditResult =
        mutate(id, MangaListEditResult.Discarded, { it is MangaListEditResult.Confirmed || it == MangaListEditResult.Discarded }, save)

    suspend fun <T> mutate(
        id: MangaId,
        discarded: T,
        mayContinue: (T) -> Boolean,
        save: suspend () -> T,
    ): T {
        val batch = synchronized(batches) { batches.getOrPut(id, ::Batch).also { it.edits++ } }
        try {
            return lock.withLock {
                if (batch.failed) return@withLock discarded
                var failed = true
                try {
                    save().also { failed = !mayContinue(it) }
                } finally {
                    if (failed) {
                        batch.failed = true
                        synchronized(batches) { batches.remove(id, batch) }
                    }
                }
            }
        } finally {
            synchronized(batches) {
                if (--batch.edits == 0) batches.remove(id, batch)
            }
        }
    }
}
