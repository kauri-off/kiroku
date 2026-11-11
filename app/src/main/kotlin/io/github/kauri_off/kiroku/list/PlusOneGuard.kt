package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.model.AnimeId

// While taps keep coming within [windowMillis] of each other, a "+1" on a row that is fading out, or on any row below
// the one that left (moving up, or just arrived), is dropped: the finger was aimed at what was there before.
class PlusOneGuard(
    private val windowMillis: Long,
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
        if (id !in ids) return false
        val burst = previous != null && at - previous < windowMillis
        val left = lastId != null && lastId != id && lastId !in ids
        if (burst && left && ids.indexOf(id) >= lastIndex) return false
        lastId = id
        lastIndex = ids.indexOf(id)
        return true
    }
}
