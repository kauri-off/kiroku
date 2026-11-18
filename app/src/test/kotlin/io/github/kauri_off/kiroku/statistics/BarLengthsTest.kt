package io.github.kauri_off.kiroku.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarLengthsTest {
    @Test
    fun `the largest value fills its bar, 0 leaves it empty, and 1 of thousands is more than nothing`() {
        val lengths = barLengths(listOf(1, 2_797, 0, 700))

        assertEquals(1f, lengths[1], 0f)
        assertEquals(0f, lengths[2], 0f)
        assertTrue(lengths[0] > 0f)
        assertEquals(700f / 2_797, lengths[3], 1e-6f)
    }

    @Test
    fun `a distribution of zeros draws every bar empty`() {
        assertEquals(listOf(0f, 0f), barLengths(listOf(0, 0)))
        assertEquals(emptyList<Float>(), barLengths(emptyList()))
    }
}
