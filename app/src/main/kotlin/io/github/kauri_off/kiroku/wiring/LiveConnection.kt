package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.repository.Connection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// The waits before the system is asked again after it refused to tell, doubling from the first up to the longest.
internal val FIRST_WAIT = 2.seconds
internal val LONGEST_WAIT = 10.minutes

// The device's connection, one system callback for every screen that waits for it, and none while none does.
internal fun liveConnection(parts: LiveParts): Connection {
    val shared =
        parts.device
            .observeOnline()
            // A system that refuses to tell is reported once and counts as online, as for a request, then is asked again.
            .retryWhen { e, attempt ->
                if (e !is Exception) return@retryWhen false
                if (attempt == 0L) {
                    parts.unexpected(e)
                    emit(true)
                }
                delay(waitAfter(attempt))
                true
            }.shareIn(parts.scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)
    return object : Connection {
        override fun observeOnline(): Flow<Boolean> = shared
    }
}

internal fun waitAfter(attempt: Long): Duration =
    (FIRST_WAIT * (1L shl attempt.coerceAtMost(20).toInt()).toDouble()).coerceAtMost(LONGEST_WAIT)
