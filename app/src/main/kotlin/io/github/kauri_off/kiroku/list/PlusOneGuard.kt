package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.model.AnimeId

// How long after the rows stop moving a "+1" on a row that moved up is still taken as aimed at what was there before.
const val PLUS_ONE_SETTLE_MILLIS = 800L

// Drops a "+1" on a row fading out, or on one that moved up, until the rows have stood; taps do not extend that.
class PlusOneGuard(
    private val moveMillis: Long,
) {
    private var shown = emptyList<AnimeId>()
    private var until = Long.MIN_VALUE

    // Rows at or below this place moved up.
    private var from = Int.MAX_VALUE

    // [ids] are the rows shown from [at] on, a time on a monotonic clock.
    fun shown(
        ids: List<AnimeId>,
        at: Long,
    ) {
        val left = shown.indices.firstOrNull { shown[it] !in ids }
        shown = ids
        if (left == null) return
        from = if (at < until) minOf(from, left) else left
        until = at + moveMillis + PLUS_ONE_SETTLE_MILLIS
    }

    // [ids] are the rows shown when the tap lands at [at].
    fun accept(
        id: AnimeId,
        ids: List<AnimeId>,
        at: Long,
    ): Boolean = id in ids && !(at < until && ids.indexOf(id) >= from)
}
