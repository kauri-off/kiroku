package io.github.kauri_off.kiroku

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen

// A screen's state is produced while a screen collects it, and for longer than a rotation takes after the last one.
val WhileShown: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)

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
