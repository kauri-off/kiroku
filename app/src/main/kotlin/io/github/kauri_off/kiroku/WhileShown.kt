package io.github.kauri_off.kiroku

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlin.time.Duration.Companion.seconds

// How a screen's state is shared: from its view model's creation, so the first frame shows what the store holds, until
// nothing has collected it for longer than a rotation takes; the next collector starts it again.
val WhileShown: SharingStarted =
    SharingStarted { collectors ->
        channelFlow {
            send(SharingCommand.START)
            collectors.collectLatest { count ->
                if (count > 0) {
                    send(SharingCommand.START)
                } else {
                    delay(STOP_AFTER)
                    send(SharingCommand.STOP)
                }
            }
        }.distinctUntilChanged()
    }

private val STOP_AFTER = 5.seconds

// A source that cannot be read emits [failed] and is read again once [retries] counts a Retry.
fun <T> Flow<T>.readAgainOnRetry(
    retries: StateFlow<Int>,
    failed: (DataError) -> T,
): Flow<T> =
    retryWhen { cause, _ ->
        if (cause !is DataException) return@retryWhen false
        val asked = retries.value
        emit(failed(cause.error))
        retries.first { it > asked }
        true
    }
