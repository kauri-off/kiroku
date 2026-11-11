package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.repository.ListChange
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.WriteFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

internal class DemoListRepository(
    entries: List<ListEntry>,
    private val server: DemoServer,
    private val signedIn: StateFlow<Boolean>,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : ListRepository {
    private sealed interface Request {
        val id: AnimeId
    }

    private class Put(
        val anime: Anime,
        val values: ListValues?,
        val epoch: Int,
    ) : Request {
        override val id: AnimeId get() = anime.id
    }

    private class Direct(
        val write: ListWrite,
        val result: CompletableDeferred<Outcome<Unit>>,
    ) : Request {
        override val id: AnimeId get() = write.anime.id
    }

    private val guard = Any()
    private val order = entries.mapTo(LinkedHashSet()) { it.anime.id }
    private val local = MutableStateFlow(entries.associateBy { it.anime.id })
    private val epochs = mutableMapOf<AnimeId, Int>()
    private val pending = mutableMapOf<AnimeId, Int>()
    private val touched = mutableMapOf<AnimeId, Long>()
    private var changes = 0L
    private val accepted = mutableListOf<Request>()
    private var running: Deferred<Outcome<Unit>>? = null
    private var following: Deferred<Outcome<Unit>>? = null
    private val queue = Channel<Request>(Channel.UNLIMITED)
    private val failures =
        MutableSharedFlow<WriteFailure>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        check(scope.isActive) { STOPPED }
        scope
            .launch {
                for (request in queue) {
                    when (request) {
                        is Put -> send(request)
                        is Direct -> send(request)
                    }
                    synchronized(guard) {
                        accepted.remove(request)
                        if (request is Put) pending[request.id] = (pending[request.id] ?: 1) - 1
                    }
                }
            }.invokeOnCompletion { stop() }
    }

    override fun observeEntries(): Flow<List<ListEntry>> =
        combine(local, signedIn) { entries, signedIn -> if (signedIn) entries.values.toList() else emptyList() }

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> =
        observeEntries().map { entries -> entries.firstOrNull { it.anime.id == id } }.distinctUntilChanged()

    override fun observeWriteFailures(): Flow<WriteFailure> = failures.asSharedFlow()

    override suspend fun sync(): Outcome<Unit> {
        if (!signedIn.value) return Outcome.Failure(DataError.SessionExpired)
        val sync =
            synchronized(guard) {
                following ?: run {
                    val previous = running
                    val started =
                        scope.async(start = CoroutineStart.LAZY) {
                            previous?.join()
                            download()
                        }
                    if (previous == null) running = started else following = started
                    started.invokeOnCompletion {
                        synchronized(guard) {
                            if (running === started) running = following.also { following = null }
                            if (following === started) following = null
                        }
                    }
                    started.start()
                    started
                }
            }
        return try {
            sync.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException(STOPPED, e)
        }
    }

    override suspend fun write(write: ListWrite): ListChange? {
        if (!signedIn.value) {
            failures.tryEmit(WriteFailure(DataError.SessionExpired, write))
            return null
        }
        return synchronized(guard) {
            check(!queue.isClosedForSend) { STOPPED }
            val id = write.anime.id
            val current = local.value[id]
            val values = write.applyTo(current, today())
            if (values == current?.values) return null
            val anime = current?.anime ?: write.anime
            val next = values?.let { ListEntry(anime, it, clock.instant()) }
            pending[id] = (pending[id] ?: 0) + 1
            set(id, next)
            accept(Put(anime, values, epochs[id] ?: 0))
            ListChange(current, next)
        }
    }

    override suspend fun send(write: ListWrite): Outcome<Unit> {
        if (!signedIn.value) return Outcome.Failure(DataError.SessionExpired)
        val result = CompletableDeferred<Outcome<Unit>>()
        synchronized(guard) {
            check(!queue.isClosedForSend) { STOPPED }
            accept(Direct(write, result))
        }
        return result.await()
    }

    private fun accept(request: Request) {
        accepted += request
        queue.trySend(request)
    }

    private suspend fun download(): Outcome<Unit> {
        val since = synchronized(guard) { changes }
        val remote =
            when (val outcome = server.download()) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value.associateBy { it.anime.id }
            }
        synchronized(guard) {
            if (!signedIn.value) return Outcome.Failure(DataError.SessionExpired)
            val next = local.value.toMutableMap()
            for (id in local.value.keys + remote.keys) {
                if ((pending[id] ?: 0) > 0 || (touched[id] ?: 0) > since) continue
                val entry = remote[id]
                if (entry == null) next.remove(id) else next[id] = entry
            }
            publish(next)
        }
        return Outcome.Success(Unit)
    }

    private suspend fun send(put: Put) {
        val id = put.id
        if (synchronized(guard) { epochs[id] ?: 0 } != put.epoch) return
        val outcome =
            if (signedIn.value) server.put(put.anime, put.values) else Outcome.Failure(DataError.SessionExpired)
        if (outcome !is Outcome.Failure) {
            synchronized(guard) { touch(id) }
            return
        }
        val failure =
            synchronized(guard) {
                epochs[id] = put.epoch + 1
                val shown = local.value[id]
                set(id, server.entry(id))
                WriteFailure(outcome.error, shown?.let { ListWrite.Save(it.anime, it.values) } ?: ListWrite.Remove(put.anime))
            }
        if (signedIn.value) failures.tryEmit(failure)
    }

    private suspend fun send(direct: Direct) {
        val write = direct.write
        val id = direct.id
        if (!signedIn.value) {
            direct.result.complete(Outcome.Failure(DataError.SessionExpired))
            return
        }
        val confirmed = server.entry(id)
        val values = write.applyTo(confirmed, today())
        val refusal =
            when {
                confirmed == null && write !is ListWrite.Save && write !is ListWrite.Remove -> DataError.NotFound
                write is ListWrite.MarkWatched && confirmed != null && confirmed.values.watchedEpisodes < write.episode - 1 ->
                    DataError.InvalidRequest
                else -> null
            }
        if (refusal != null) {
            direct.result.complete(Outcome.Failure(refusal))
            return
        }
        if (values == confirmed?.values) {
            synchronized(guard) { if ((pending[id] ?: 0) == 0 && local.value[id] != confirmed) set(id, confirmed) }
            direct.result.complete(Outcome.Success(Unit))
            return
        }
        val outcome = server.put(confirmed?.anime ?: write.anime, values)
        if (outcome is Outcome.Success) {
            synchronized(guard) { if ((pending[id] ?: 0) == 0) set(id, server.entry(id)) else touch(id) }
        }
        direct.result.complete(outcome)
    }

    private fun stop() {
        val directs =
            synchronized(guard) {
                queue.close()
                val stopped = accepted.toList()
                accepted.clear()
                for (id in stopped.filterIsInstance<Put>().map { it.id }.toSet()) {
                    epochs[id] = (epochs[id] ?: 0) + 1
                    pending.remove(id)
                    set(id, server.entry(id))
                }
                stopped.filterIsInstance<Direct>()
            }
        directs.forEach { it.result.completeExceptionally(IllegalStateException(STOPPED)) }
    }

    private fun touch(id: AnimeId) {
        touched[id] = ++changes
    }

    private fun set(
        id: AnimeId,
        entry: ListEntry?,
    ) {
        touch(id)
        publish(local.value.toMutableMap().apply { if (entry == null) remove(id) else put(id, entry) })
    }

    private fun publish(entries: Map<AnimeId, ListEntry>) {
        order += entries.keys
        local.value = order.mapNotNull { id -> entries[id]?.let { id to it } }.toMap()
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private companion object {
        const val STOPPED = "The demo list's scope has ended"
    }
}
