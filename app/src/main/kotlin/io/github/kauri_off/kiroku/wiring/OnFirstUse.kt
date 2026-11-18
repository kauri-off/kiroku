package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.error.endedCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// Built at its first use, off the main thread; a build that met the end of [scope] ends as that end does for its caller.
internal suspend fun <T> Lazy<T>.onFirstUse(scope: CoroutineScope): T {
    if (isInitialized()) return value
    return try {
        withContext(Dispatchers.IO) { value }
    } catch (e: IllegalStateException) {
        if (scope.isActive) throw e
        scope.endedCall(e)
    }
}
