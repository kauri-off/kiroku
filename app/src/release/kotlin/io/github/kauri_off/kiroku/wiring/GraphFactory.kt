package io.github.kauri_off.kiroku.wiring

import android.util.Log
import coil3.intercept.Interceptor

// A release build logs an exception the list did not expect, and the list goes on.
internal val reportUnexpected: (Throwable) -> Unit = { Log.e(LOG_TAG, UNEXPECTED, it) }

internal val demoGraphFactory: DemoGraphFactory? = null

internal val standInPosters: Interceptor? = null
