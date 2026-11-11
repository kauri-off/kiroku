package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.model.AnimeId

// Quick taps on the place of a row that has just left the tab were meant for it, not for the row sliding into its
// place nor for the row fading out: such a tap is dropped while the taps keep coming within [burstMillis] of each other.
class PlusOneGuard(
    private val burstMillis: Long,
) {
    private var lastAt: Long? = null
    private var lastId: AnimeId? = null
    private var lastIndex = -1

    // [ids] are the rows shown when the tap lands, [at] its time on a monotonic clock.
    fun accept(
        id: AnimeId,
        ids: List<AnimeId>,
        at: Long,
    ): Boolean {
        val previous = lastAt
        lastAt = at
        // A row on its way out is no longer on the list.
        if (id !in ids) return false
        val burst = previous != null && at - previous < burstMillis
        val left = lastId != null && lastId != id && lastId !in ids
        if (burst && left && ids.indexOf(id) == lastIndex) return false
        lastId = id
        lastIndex = ids.indexOf(id)
        return true
    }
}
