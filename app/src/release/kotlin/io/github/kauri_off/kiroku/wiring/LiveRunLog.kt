package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.notify.RunReport

// A release build keeps nothing of the notification engine's runs.
@Suppress("UNUSED_PARAMETER")
internal fun liveRunLog(parts: LiveParts): ((RunReport) -> Unit)? = null
