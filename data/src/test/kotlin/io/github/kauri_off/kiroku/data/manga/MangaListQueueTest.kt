package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.repository.MangaListEditResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaListQueueTest {
    private val queue = MangaListQueue()
    private val entry = MangaListEntry(MangaId(1), Titles("Title"), MangaReadingStatus("reading"))
    private val confirmed = MangaListEditResult.Confirmed(entry)

    @Test
    fun `queued edits to an entry are sent in order after a confirmation`() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val sent = mutableListOf<Int>()
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.edit(entry.id) {
                        release.await()
                        sent += 1
                        confirmed
                    }
                }
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.edit(entry.id) {
                        sent += 2
                        confirmed
                    }
                }
            assertEquals(emptyList<Int>(), sent)
            release.complete(Unit)
            assertEquals(confirmed, first.await())
            assertEquals(confirmed, second.await())
            assertEquals(listOf(1, 2), sent)
        }

    @Test
    fun `a failed edit discards waiting edits for that entry and permits a new attempt`() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val failed = MangaListEditResult.Failed(DataError.InvalidRequest)
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.edit(entry.id) {
                        release.await()
                        failed
                    }
                }
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.edit(entry.id) { error("Discarded edits cannot send") }
                }
            val other =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.edit(MangaId(2)) { confirmed }
                }

            release.complete(Unit)
            assertEquals(failed, first.await())
            assertEquals(MangaListEditResult.Discarded, second.await())
            assertEquals(confirmed, other.await())
            assertEquals(confirmed, queue.edit(entry.id) { confirmed })
        }
}
