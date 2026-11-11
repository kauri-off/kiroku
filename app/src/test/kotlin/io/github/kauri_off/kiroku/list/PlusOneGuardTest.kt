package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.model.AnimeId
import org.junit.Assert.assertEquals
import org.junit.Test

class PlusOneGuardTest {
    private val x = AnimeId(1)
    private val y = AnimeId(2)
    private val z = AnimeId(3)
    private val window = 500L

    @Test
    fun `quick taps on the place of a row that left are dropped until the taps pause`() {
        val guard = PlusOneGuard(window)
        val taps =
            listOf(
                guard.accept(x, listOf(z, x, y), at = 0),
                guard.accept(x, listOf(z, x, y), at = 150),
                // x has left; y slid into its place.
                guard.accept(y, listOf(z, y), at = 300),
                guard.accept(y, listOf(z, y), at = 450),
                guard.accept(y, listOf(z, y), at = 450 + window),
            )
        assertEquals(listOf(true, true, false, false, true), taps)
    }

    // A2's Q1: the finger aims at the row below the one that left, which has moved up too.
    @Test
    fun `quick taps on any row that moved up after a row left are dropped until the taps pause`() {
        val w = AnimeId(4)
        val guard = PlusOneGuard(window)
        guard.accept(x, listOf(z, x, y, w), at = 0)
        assertEquals(false, guard.accept(w, listOf(z, y, w), at = 200))
        assertEquals(false, guard.accept(y, listOf(z, y, w), at = 350))
        assertEquals(true, guard.accept(w, listOf(z, y, w), at = 350 + window))
    }

    @Test
    fun `a quick tap on a row above the one that left still counts`() {
        val guard = PlusOneGuard(window)
        guard.accept(x, listOf(z, x, y), at = 0)
        assertEquals(true, guard.accept(z, listOf(z, y), at = 100))
    }

    @Test
    fun `quick taps on rows that stay all count`() {
        val guard = PlusOneGuard(window)
        assertEquals(List(4) { true }, List(4) { guard.accept(x, listOf(x, y), at = it * 50L) })
        assertEquals(true, guard.accept(y, listOf(x, y), at = 250))
    }

    @Test
    fun `a tap on a row that is fading out is dropped`() {
        val guard = PlusOneGuard(window)
        assertEquals(false, guard.accept(x, listOf(z, y), at = 5_000))
    }
}
