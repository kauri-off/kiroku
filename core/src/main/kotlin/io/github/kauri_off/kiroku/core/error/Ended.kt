package io.github.kauri_off.kiroku.core.error

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job

// Ends a call that met this scope's end: as the caller's cancellation if it was cancelled or runs in this scope, else with [failure].
suspend fun CoroutineScope.endedCall(failure: Throwable): Nothing {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    if (!isActive && coroutineContext.job.holds(caller.job)) throw CancellationException(failure.message, failure)
    throw failure
}

private fun Job.holds(job: Job): Boolean = children.any { it === job || it.holds(job) }
