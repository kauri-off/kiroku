package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// The airing data of a live source, of which there is none yet.
@Suppress("UNUSED_PARAMETER")
internal fun liveAiring(parts: LiveParts): AiringRepository =
    object : AiringRepository {
        private val unavailable = Outcome.Failure(DataError.ServerError)

        override fun observeAiring(): Flow<Map<AnimeId, Airing>> = flowOf(emptyMap())

        override fun observePageAiring(page: Anime): Flow<Map<AnimeId, Airing>> = flowOf(emptyMap())

        override fun observeSchedules(): Flow<Map<AnimeId, AiringSchedule>> = flowOf(emptyMap())

        override suspend fun refreshStale(): Outcome<Unit> = unavailable

        override suspend fun refresh(): Outcome<Unit> = unavailable

        override suspend fun refreshStale(page: Anime): Outcome<Unit> = unavailable

        override suspend fun refresh(page: Anime): Outcome<Unit> = unavailable
    }
