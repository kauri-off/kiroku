package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.data.anime.MalAnimeRepository
import io.github.kauri_off.kiroku.data.anime.MalTitles
import io.github.kauri_off.kiroku.data.db.TitleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.time.Instant

// The titles of a live source: its database's, refreshed from MAL's pages.
internal fun liveTitles(
    parts: LiveParts,
    adult: suspend () -> Boolean = { false },
    build: (LiveParts) -> AnimeRepository = { titlesOf(it, adult) },
): AnimeRepository = TitlesOnFirstUse(parts.scope) { build(parts) }

private fun titlesOf(
    parts: LiveParts,
    adult: suspend () -> Boolean,
): AnimeRepository {
    val store = TitleStore(parts.database, Dispatchers.IO, parts.unexpected)
    return MalAnimeRepository(MalTitles(parts.http), store, parts.clock, adult, parts.unexpected)
}

// The repository and the database under it are built at its first call, off the main thread.
private class TitlesOnFirstUse(
    private val scope: CoroutineScope,
    build: () -> AnimeRepository,
) : AnimeRepository {
    private val built = lazy(build)

    private suspend fun titles(): AnimeRepository = built.onFirstUse(scope)

    override fun observeAnime(id: AnimeId): Flow<Anime?> = flow { emitAll(titles().observeAnime(id)) }

    override fun observeDetails(id: AnimeId): Flow<AnimeDetails?> = flow { emitAll(titles().observeDetails(id)) }

    override fun observeDetailsOf(ids: Set<AnimeId>): Flow<Map<AnimeId, AnimeDetails>> = flow { emitAll(titles().observeDetailsOf(ids)) }

    override fun observeRefreshedAt(id: AnimeId): Flow<Instant?> = flow { emitAll(titles().observeRefreshedAt(id)) }

    override fun observePagesRead(): Flow<Set<AnimeId>> = flow { emitAll(titles().observePagesRead()) }

    override suspend fun refresh(id: AnimeId) = titles().refresh(id)
}
