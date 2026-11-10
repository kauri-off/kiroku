package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.WriteFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

internal class DemoListRepository(
    entries: List<ListEntry>,
    private val server: DemoServer,
    private val signedIn: StateFlow<Boolean>,
    private val clock: Clock,
    scope: CoroutineScope,
) : ListRepository {
    private sealed interface Request

    private class Put(
        val anime: Anime,
        val values: ListValues?,
        val epoch: Int,
    ) : Request

    private class Direct(
        val write: ListWrite,
        val result: CompletableDeferred<Outcome<Unit>>,
    ) : Request

    private val guard = Any()
    private val local = MutableStateFlow(entries.associateBy { it.anime.id })
    private val epochs = mutableMapOf<AnimeId, Int>()
    private val queue = Channel<Request>(Channel.UNLIMITED)
    private val failures =
        MutableSharedFlow<WriteFailure>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            for (request in queue) {
                when (request) {
                    is Put -> send(request)
                    is Direct -> send(request)
                }
            }
        }
    }

    override fun observeEntries(): Flow<List<ListEntry>> =
        combine(local, signedIn) { entries, signedIn -> if (signedIn) entries.values.toList() else emptyList() }

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> =
        observeEntries().map { entries -> entries.firstOrNull { it.anime.id == id } }.distinctUntilChanged()

    override fun observeWriteFailures(): Flow<WriteFailure> = failures.asSharedFlow()

    override suspend fun sync(): Outcome<Unit> = server.load(needsSignIn = true).toOutcome()

    override suspend fun write(write: ListWrite) {
        synchronized(guard) {
            val id = write.anime.id
            val current = local.value[id]
            val values = write.applyTo(current, today())
            if (values == current?.values) return
            val anime = current?.anime ?: write.anime
            set(id, values?.let { ListEntry(anime, it, clock.instant()) })
            queue.trySend(Put(anime, values, epochs[id] ?: 0))
        }
    }

    override suspend fun send(write: ListWrite): Outcome<Unit> {
        val result = CompletableDeferred<Outcome<Unit>>()
        queue.trySend(Direct(write, result))
        return result.await()
    }

    private suspend fun send(put: Put) {
        val id = put.anime.id
        if (synchronized(guard) { epochs[id] ?: 0 } != put.epoch) return
        val outcome = server.put(put.anime, put.values, clock.instant())
        if (outcome !is Outcome.Failure) return
        val failure =
            synchronized(guard) {
                epochs[id] = put.epoch + 1
                val shown = local.value[id]
                set(id, server.entry(id))
                WriteFailure(outcome.error, shown?.let { ListWrite.Save(it.anime, it.values) } ?: ListWrite.Remove(put.anime))
            }
        failures.tryEmit(failure)
    }

    private suspend fun send(direct: Direct) {
        val id = direct.write.anime.id
        val (current, values) =
            synchronized(guard) {
                val current = local.value[id]
                current to direct.write.applyTo(current, today())
            }
        if (values == current?.values) {
            direct.result.complete(Outcome.Success(Unit))
            return
        }
        val outcome = server.put(current?.anime ?: direct.write.anime, values, clock.instant())
        if (outcome is Outcome.Success) {
            synchronized(guard) { if (local.value[id] == current) set(id, server.entry(id)) }
        }
        direct.result.complete(outcome)
    }

    private fun set(
        id: AnimeId,
        entry: ListEntry?,
    ) {
        local.value = local.value.toMutableMap().apply { if (entry == null) remove(id) else put(id, entry) }
    }

    private fun today(): LocalDate = LocalDate.now(clock)
}
