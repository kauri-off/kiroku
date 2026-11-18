package io.github.kauri_off.kiroku.wiring

// A release build keeps nothing of the notification engine's runs.
@Suppress("UNUSED_PARAMETER")
internal fun liveRunLog(parts: LiveParts): KeptRuns? = null
