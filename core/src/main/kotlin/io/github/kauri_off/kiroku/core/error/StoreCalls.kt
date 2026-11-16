package io.github.kauri_off.kiroku.core.error

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// A store call that threw; it fails the one call an engine was asked in as a storage error.
internal class StoreFailure(
    cause: Throwable,
) : Exception(cause)

// How an engine takes its store's exceptions: each fails one call, and only the first of a streak is reported.
internal class StoreCalls(
    private val scope: CoroutineScope,
    private val unexpected: (Throwable) -> Unit,
) {
    private val unreadable = AtomicBoolean()
    private val unwritable = AtomicBoolean()

    suspend fun <T> reading(call: suspend () -> T): T = guarded(unreadable, call)

    suspend fun <T> writing(call: suspend () -> T): T = guarded(unwritable, call)

    fun <T> observing(stored: Flow<T>): Flow<T> =
        stored.onEach { unreadable.set(false) }.catch { throw if (it is Exception) failure(unreadable, it) else it }

    // A cancellation of the caller or of the engine's scope ends the call; any other is a failure like any exception.
    suspend fun ended(e: Exception) {
        if (e !is CancellationException) return
        currentCoroutineContext().ensureActive()
        if (!scope.isActive) throw e
    }

    private suspend fun <T> guarded(
        failing: AtomicBoolean,
        call: suspend () -> T,
    ): T =
        try {
            call().also { failing.set(false) }
        } catch (e: Exception) {
            throw failure(failing, e)
        }

    private suspend fun failure(
        failing: AtomicBoolean,
        e: Exception,
    ): Exception {
        ended(e)
        if (failing.compareAndSet(false, true)) unexpected(e)
        return StoreFailure(e)
    }

    companion object {
        const val ATTEMPTS = 3
        val RETRY: Duration = 1.seconds
    }
}

// A flow that fails with the store is tried a few times in a row, then ends as a storage error.
internal fun <T> Flow<T>.retriedOverStore(): Flow<T> =
    flow {
        var failed = 0
        emitAll(
            onEach { failed = 0 }
                .retryWhen { cause, _ ->
                    (cause is StoreFailure && ++failed < StoreCalls.ATTEMPTS).also { if (it) delay(StoreCalls.RETRY) }
                }.catch { throw if (it is StoreFailure) DataException(DataError.StorageError, it.cause) else it },
        )
    }
