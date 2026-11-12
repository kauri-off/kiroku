package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.model.AnimeId
import org.junit.Assert.assertEquals
import org.junit.Test

class PlusOneGuardTest {
    private val x = AnimeId(1)
    private val y = AnimeId(2)
    private val z = AnimeId(3)
    private val w = AnimeId(4)
    private val move = 200L

    // x leaves at 1 000 ms; the rows below it move until 1 200 ms and stand for the settle time after that.
    private val closes = 1_000 + move + PLUS_ONE_SETTLE_MILLIS

    private fun leaving(): PlusOneGuard =
        PlusOneGuard(move).apply {
            shown(listOf(z, x, y, w), at = 0)
            shown(listOf(z, y, w), at = 1_000)
        }

    @Test
    fun `a tap on the place of a row that left is dropped until the rows have stood for the settle time`() {
        val guard = leaving()
        assertEquals(false, guard.accept(y, listOf(z, y, w), at = 1_000))
        assertEquals(false, guard.accept(y, listOf(z, y, w), at = closes - 1))
        assertEquals(true, guard.accept(y, listOf(z, y, w), at = closes))
    }

    // A3's Q1b: the finger aims at the row below the one that left, at an ordinary pace.
    @Test
    fun `a tap on any row that moved up is dropped until the same time`() {
        val guard = leaving()
        assertEquals(false, guard.accept(w, listOf(z, y, w), at = 1_600))
        assertEquals(false, guard.accept(w, listOf(z, y, w), at = closes - 1))
        assertEquals(true, guard.accept(w, listOf(z, y, w), at = closes))
    }

    // B3's m2: a fast run of taps does not keep the window open; the taps after it closes count.
    @Test
    fun `taps do not extend the time`() {
        val guard = leaving()
        val taps = (1_000L..3_000L step 100).map { at -> at to guard.accept(w, listOf(z, y, w), at) }
        assertEquals(taps.map { (at, _) -> at >= closes }, taps.map { it.second })
    }

    @Test
    fun `a tap on a row above the one that left counts`() {
        val guard = leaving()
        assertEquals(true, guard.accept(z, listOf(z, y, w), at = 1_000))
    }

    @Test
    fun `taps on rows while no row has left all count`() {
        val guard = PlusOneGuard(move)
        guard.shown(listOf(x, y), at = 0)
        assertEquals(List(4) { true }, List(4) { guard.accept(x, listOf(x, y), at = it * 50L) })
        assertEquals(true, guard.accept(y, listOf(x, y), at = 250))
    }

    @Test
    fun `a second row leaving keeps the rows below the higher one guarded until the rows stand again`() {
        val guard = leaving()
        guard.shown(listOf(y, w), at = 1_500)
        val later = 1_500 + move + PLUS_ONE_SETTLE_MILLIS
        assertEquals(false, guard.accept(y, listOf(y, w), at = later - 1))
        assertEquals(true, guard.accept(y, listOf(y, w), at = later))
    }

    @Test
    fun `a tap on a row that is fading out is dropped`() {
        val guard = PlusOneGuard(move)
        assertEquals(false, guard.accept(x, listOf(z, y), at = 5_000))
    }
}
