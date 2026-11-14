package io.github.kauri_off.kiroku.wiring

import coil3.intercept.Interceptor

// A release build logs the types of an exception nobody expected, never its message, and goes on.
internal val reportUnexpected: (Throwable) -> Unit = ::logTypes

internal val requestLog: ((String) -> Unit)? = null

internal val otherSource: OtherSource? = null

internal val standInPosters: Interceptor? = null
