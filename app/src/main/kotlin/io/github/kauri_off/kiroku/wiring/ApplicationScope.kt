package io.github.kauri_off.kiroku.wiring

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// The scope of work that outlives a screen, the one scope the app makes: an exception escaping its work is reported, an Error is not.
internal fun applicationScope(report: (Throwable) -> Unit): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> if (e is Exception) report(e) else throw e })
