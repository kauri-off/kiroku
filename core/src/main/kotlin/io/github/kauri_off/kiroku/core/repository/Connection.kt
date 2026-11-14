package io.github.kauri_off.kiroku.core.repository

import kotlinx.coroutines.flow.Flow

// Whether the device has a network that reaches the internet: the "No connection" of section 2.3.
interface Connection {
    // Emits the current answer at once and each change after it.
    fun observeOnline(): Flow<Boolean>
}
