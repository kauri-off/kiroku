package io.github.kauri_off.kiroku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

// Within a second the flow of use is not interrupted, so a shorter wait shows no indicator.
val PATIENCE = 1.seconds

// Returns once a wait has lasted PATIENCE, timed on the main thread's clock.
suspend fun outlastPatience() = withContext(Dispatchers.Main) { delay(PATIENCE) }
