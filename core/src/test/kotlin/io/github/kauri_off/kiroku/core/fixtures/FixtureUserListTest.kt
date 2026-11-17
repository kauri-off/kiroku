package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.firstDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureUserListTest {
    @Test
    fun `the public user's list has several thousand entries, each title once and under a made-up id`() {
        FixtureSets.all.forEach { (name, set) ->
            val list = set.otherUserList
            assertTrue(name, list.size in 4_000..6_000)
            assertEquals(name, list.size, list.map { it.anime.id }.toSet().size)
            assertEquals(name, list.size, list.map { it.anime.titles.romaji }.toSet().size)
            val named = set.anime.mapTo(mutableSetOf()) { it.titles.romaji }
            assertEquals(
                name,
                emptyList<String>(),
                list.drop(set.otherUserEntries.size).map { it.anime.titles.romaji }.filter { it in named },
            )
            assertEquals(name, emptyList<Long>(), list.map { it.anime.id }.filterNot { it.madeUp }.map { it.value })
            assertEquals(name, set.otherUserEntries, list.take(set.otherUserEntries.size))
            val known = set.anime.mapTo(mutableSetOf()) { it.id }
            assertEquals(name, set.otherUserEntries.size, list.count { it.anime.id in known })
        }
    }

    @Test
    fun `it has every status and format, and titles that lack each value a statistic reads`() {
        val list = Fixtures.reference.otherUserList
        assertEquals(ListStatus.entries.toSet(), list.map { it.status }.toSet())
        assertTrue(list.count { it.status == ListStatus.Completed } > list.size / 2)
        val formats = list.map { it.anime.mediaType }.toSet()
        assertTrue(formats.containsAll(listOf(MediaType.Tv, MediaType.Movie, MediaType.Ova, MediaType.Ona, MediaType.Special, null)))
        assertEquals((2..10).toSet(), list.mapNotNull { it.values.score }.toSet())
        for (missing in listOf<(Int) -> Boolean>(
            { list[it].values.score == null && list[it].status == ListStatus.Completed },
            { list[it].anime.startDate == null },
            { list[it].anime.genres == null },
            { list[it].anime.averageEpisodeDuration == null },
            { list[it].anime.malEpisodes == null },
        )) {
            assertTrue(list.indices.any(missing))
        }
    }

    @Test
    fun `nothing of it is in the future, and it is the same list at every read of a day`() {
        FixtureSets.all.forEach { (name, set) ->
            val today = set.now.atZone(FixtureTime.zone).toLocalDate()
            val generated = set.otherUserList.drop(set.otherUserEntries.size)
            assertEquals(name, FixtureUser.generatedEntries(today), generated)
            assertTrue(name, generated.all { it.anime.airingStatus == AiringStatus.Finished })
            assertTrue(name, generated.all { it.updatedAt < set.now })
            assertTrue(name, generated.all { entry -> entry.anime.startDate?.let { it.firstDay() < today } != false })
            assertTrue(name, generated.all { it.values.watchedEpisodes <= (it.anime.malEpisodes ?: Int.MAX_VALUE) })
        }
    }
}
