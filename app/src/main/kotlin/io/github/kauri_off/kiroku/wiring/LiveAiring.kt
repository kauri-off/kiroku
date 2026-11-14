package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// The airing data of a live source, of which there is none yet.
@Suppress("UNUSED_PARAMETER")
internal fun liveAiring(parts: LiveParts): AiringRepository =
    object : AiringRepository {
        private val unavailable = Outcome.Failure(DataError.ServerError)

        override fun observeSchedules(): Flow<Map<AnimeId, AiringSchedule>> = flowOf(emptyMap())

        override fun observeAiring(): Flow<Map<AnimeId, Airing>> = flowOf(emptyMap())

        override suspend fun refresh(ids: Set<AnimeId>): Outcome<Unit> = unavailable

        override suspend fun fetch(id: AnimeId): Outcome<AiringSchedule?> = unavailable
    }
