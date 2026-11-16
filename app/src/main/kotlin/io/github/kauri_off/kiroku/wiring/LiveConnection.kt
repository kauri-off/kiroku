package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.repository.Connection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.shareIn

// The device's connection, one system callback for every screen that waits for it, and none while none does.
internal fun liveConnection(parts: LiveParts): Connection {
    val shared =
        parts.device
            .observeOnline()
            // A system that refuses to tell is reported and counts as online, as for a request; the next first screen asks again.
            .catch { e ->
                if (e !is Exception) throw e
                parts.unexpected(e)
                emit(true)
            }.shareIn(parts.scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)
    return object : Connection {
        override fun observeOnline(): Flow<Boolean> = shared
    }
}
