package io.github.kauri_off.kiroku.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringRulesTest {
    @Test
    fun aNumberIsJoinedToItsNeighbourInEveryString() {
        val strings = stringResources()
        assertTrue(strings.any { it.first.startsWith("values-ru/") })
        assertEquals(emptyList<String>(), strings.flatMap { (name, text) -> numberSpaceProblems(name, text) })
    }

    @Test
    fun aBreakingSpaceNextToANumberFails() {
        for (planted in listOf(
            "%1\$d / %2\$d",
            "TV · 2024 · 24 episodes",
            "вышло ~%3\$d",
            "К просмотру: %d",
            "Episode 15",
            "%d to watch",
            "Saturdays at 17:30",
        )) {
            assertTrue(planted, numberSpaceProblems("a", planted).isNotEmpty())
        }
        for (clean in listOf("TV · 2024", "%1\$s · %2\$s", "%1\$d / %2\$d · вышло ~%3\$d", "%1\$s, %2\$s")) {
            assertEquals(clean, emptyList<String>(), numberSpaceProblems("a", clean))
        }
    }
}
