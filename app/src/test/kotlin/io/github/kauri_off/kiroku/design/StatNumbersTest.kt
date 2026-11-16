package io.github.kauri_off.kiroku.design

import io.github.kauri_off.kiroku.design.component.rowWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatNumbersTest {
    @Test
    fun numbersShareTheRowEquallyWhenEveryLongestWordFits() {
        assertEquals(listOf(100, 100, 100), rowWidths(listOf(60, 90, 100), width = 320, gap = 10))
    }

    @Test
    fun aLongerWordTakesItsWidthAndTheOthersShareTheRest() {
        assertEquals(listOf(130, 85, 85), rowWidths(listOf(130, 40, 80), width = 320, gap = 10))
    }

    @Test
    fun numbersWhoseLongestWordsDoNotFitAreStacked() {
        assertNull(rowWidths(listOf(130, 100, 80), width = 320, gap = 10))
    }
}
