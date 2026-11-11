package io.github.kauri_off.kiroku.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringRulesTest {
    @Test
    fun aNumberIsJoinedToItsNeighbourInEveryString() {
        val strings = stringResources()
        assertTrue(strings.any { it.name.startsWith("values-ru/") })
        assertTrue(strings.any { it.plural })
        assertEquals(emptyList<String>(), strings.flatMap { numberSpaceProblems(it.name, it.text, it.plural) })
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
            "Episode %1\$d watched",
            "Saturdays at 17:30",
        )) {
            assertTrue(planted, numberSpaceProblems("a", planted).isNotEmpty())
        }
        for (clean in listOf(
            "TV · 2024",
            "%1\$s · %2\$s",
            "%1\$d\u00A0/\u00A0%2\$d · вышло\u00A0~%3\$d",
            "%1\$s, %2\$s",
            "See all: %1\$s",
        )) {
            assertEquals(clean, emptyList<String>(), numberSpaceProblems("a", clean))
        }
        for (joined in listOf("in\u00A0%d days", "Вышло\u00A0%d серий", "Episode\u00A0%1\$d watched")) {
            assertTrue(joined, numberSpaceProblems("values/strings_list.xml a", joined).isNotEmpty())
        }
        assertEquals(
            emptyList<String>(),
            numberSpaceProblems("values-ru/strings_list.xml list_episode_watched", "Серия\u00A0%1\$d просмотрена"),
        )
        assertTrue(numberSpaceProblems("values/strings_list.xml list_episode_watched", "Episode %1\$d watched").isNotEmpty())
        assertEquals(
            emptyList<String>(),
            numberSpaceProblems("values-ru/strings_list.xml list_progress", "Просмотрено %1\$d\u00A0из\u00A0%2\$d"),
        )
        for (planted in listOf("Просмотрено %1\$d из %2\$d", "Seen by %1\$d of %2\$d")) {
            assertTrue(planted, numberSpaceProblems("values/strings_list.xml list_progress", planted).isNotEmpty())
        }
        assertTrue(numberSpaceProblems("values/strings_list.xml a", "Watched %1\$d").isNotEmpty())
        for (planted in listOf("%1\$s members", "%s участников", "%1\$s / %2\$s")) {
            assertTrue(planted, numberSpaceProblems("a", planted, plural = true).isNotEmpty())
        }
        assertEquals(emptyList<String>(), numberSpaceProblems("a", "%1\$s\u00A0members", plural = true))
    }
}
