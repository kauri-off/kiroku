package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.repository.Connection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

// The device's connection, one system callback for every screen that waits for it, and none while none does.
internal fun liveConnection(parts: LiveParts): Connection {
    val shared =
        parts.device
            .observeOnline()
            .shareIn(parts.scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)
    return object : Connection {
        override fun observeOnline(): Flow<Boolean> = shared
    }
}
