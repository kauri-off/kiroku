package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SessionOwner
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class StoredManga(
    val details: MangaDetails,
    val fetchedAt: Instant,
)

interface MangaRepository {
    fun observeOwner(): Flow<SessionOwner>

    suspend fun stored(
        id: MangaId,
        owner: SessionOwner,
    ): Outcome<StoredManga?>

    suspend fun refresh(
        id: MangaId,
        owner: SessionOwner,
    ): Outcome<StoredManga>
}
