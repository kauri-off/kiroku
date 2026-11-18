package io.github.kauri_off.kiroku.core.error

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job

// True when the scope has ended and the caller runs in it: the end is then the caller's own cancellation, also before it has reached it.
internal suspend fun CoroutineScope.endsItsCaller(): Boolean = !isActive && coroutineContext.job.holds(currentCoroutineContext().job)

private fun Job.holds(job: Job): Boolean = children.any { it === job || it.holds(job) }
