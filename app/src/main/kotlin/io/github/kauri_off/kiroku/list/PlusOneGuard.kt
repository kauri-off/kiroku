package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.model.AnimeId

// How long after the rows stop moving a "+1" on a row that moved up is still taken as aimed at what was there before.
const val PLUS_ONE_SETTLE_MILLIS = 800L

// Drops a "+1" on a row fading out, or on one that moved up, until the rows have stood; taps do not extend that.
class PlusOneGuard(
    // How long the rows take to close up; it follows the window's height.
    var moveMillis: Long,
) {
    private var shown = emptyList<AnimeId>()
    private var eligible: Set<AnimeId>? = null
    private var until = Long.MIN_VALUE

    // Rows at or below this place moved up.
    private var from = Int.MAX_VALUE

    // [ids] are the rows shown from [at] on, a time on a monotonic clock.
    fun shown(
        ids: List<AnimeId>,
        at: Long,
        eligible: Set<AnimeId>? = null,
    ) {
        val left =
            if (shown == ids) {
                -1
            } else {
                val present = ids.toHashSet()
                shown.indexOfFirst { it !in present }
            }
        val previous = this.eligible
        val shrank =
            if (previous != null && eligible != null && previous != eligible) {
                ids.indexOfFirst { it in previous && it !in eligible }
            } else {
                -1
            }
        shown = ids
        this.eligible = eligible
        val moved =
            minOf(
                if (left >= 0) left else Int.MAX_VALUE,
                if (shrank >= 0 && shrank < ids.lastIndex) shrank + 1 else Int.MAX_VALUE,
            )
        if (moved == Int.MAX_VALUE) return
        from = if (at < until) minOf(from, moved) else moved
        until = at + moveMillis + PLUS_ONE_SETTLE_MILLIS
    }

    // [ids] are the rows shown when the tap lands at [at].
    fun accept(
        id: AnimeId,
        ids: List<AnimeId>,
        at: Long,
    ): Boolean = id in ids && eligible?.contains(id) != false && !(at < until && ids.indexOf(id) >= from)
}
