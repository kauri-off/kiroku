package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.notificationlog.RunLog

// The runs of a live source's notification engine, kept in a file of the source's own for the debug menu.
internal fun liveRunLog(parts: LiveParts): RunLog = RunLog(parts.file(RUNS))

private val RUNS = notShown("runs")
