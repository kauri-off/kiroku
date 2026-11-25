package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaId
import java.time.Instant

data class StoredManga(
    val details: MangaDetails,
    val fetchedAt: Instant,
)

interface MangaRepository {
    suspend fun stored(id: MangaId): Outcome<StoredManga?>

    suspend fun refresh(id: MangaId): Outcome<StoredManga>
}
