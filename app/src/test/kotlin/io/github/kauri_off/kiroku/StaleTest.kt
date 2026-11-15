package io.github.kauri_off.kiroku

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

// How a screen reads a title's freshness from the store, before the rule of :core judges it.
class StaleTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val id = FixtureAnime.exactToWatch.id

    // A store that holds the details' time but not the title, as for one opened from a related row.
    private class Store(
        private val title: Anime?,
        private val refreshedAt: Instant?,
        private val unreadable: Boolean = false,
    ) : AnimeRepository {
        private fun <T> read(value: T): Flow<T> = if (unreadable) flow { throw DataException(DataError.StorageError) } else flowOf(value)

        override fun observeAnime(id: AnimeId): Flow<Anime?> = read(title)

        override fun observeDetails(id: AnimeId): Flow<AnimeDetails?> = read(null)

        override fun observeDetailsOf(ids: Set<AnimeId>): Flow<Map<AnimeId, AnimeDetails>> = read(emptyMap())

        override fun observeRefreshedAt(id: AnimeId): Flow<Instant?> = read(refreshedAt)

        override fun observePagesRead(): Flow<Set<AnimeId>> = read(emptySet())

        override suspend fun refresh(id: AnimeId): Outcome<Unit> = Outcome.Success(Unit)
    }

    private val sevenHoursAgo = now - Duration.ofHours(7)

    @Test
    fun `a title the store does not hold is judged by what the screen knows of it`() =
        runTest {
            assertTrue("airing", Store(null, sevenHoursAgo).stale(id, FixtureAnime.exactToWatch, now))
            assertFalse("finished", Store(null, sevenHoursAgo).stale(id, FixtureAnime.plannedFinished, now))
        }

    @Test
    fun `the store's title wins over what the screen knows`() =
        runTest { assertFalse(Store(FixtureAnime.plannedFinished, sevenHoursAgo).stale(id, FixtureAnime.exactToWatch, now)) }

    @Test
    fun `a store that cannot be read holds nothing fresh`() =
        runTest { assertTrue(Store(FixtureAnime.plannedFinished, now, unreadable = true).stale(id, FixtureAnime.plannedFinished, now)) }
}
