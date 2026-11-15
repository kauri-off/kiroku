package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.data.session.MalSession

// A release build holds no recorder.
@Suppress("UNUSED_PARAMETER")
internal fun liveRecorder(
    parts: LiveParts,
    session: Lazy<MalSession>,
): Nothing? = null
