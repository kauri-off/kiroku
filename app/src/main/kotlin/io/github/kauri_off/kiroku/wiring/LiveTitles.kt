package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// The titles of a live source, of which nothing is known and nothing can be fetched yet.
internal fun liveTitles(): AnimeRepository =
    object : AnimeRepository {
        override fun observeAnime(id: AnimeId): Flow<Anime?> = flowOf(null)

        override fun observeDetails(id: AnimeId): Flow<AnimeDetails?> = flowOf(null)

        override suspend fun refresh(id: AnimeId): Outcome<Unit> = Outcome.Failure(DataError.ServerError)
    }
