package io.github.kauri_off.kiroku.data.manga

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaJsonOnDeviceTest {
    @Test
    fun matchesAdjacentTokensAcrossTheWholeObject() {
        assertTrue(""" {"nested": [1, {"enabled": true}, null], "score": 0} """.isStrictMangaObject())
        assertFalse(""" {"nested": [1, @null], "score": 0} """.isStrictMangaObject())
        assertFalse(""" {"nested": [1], "score": 0} @ """.isStrictMangaObject())
    }
}
