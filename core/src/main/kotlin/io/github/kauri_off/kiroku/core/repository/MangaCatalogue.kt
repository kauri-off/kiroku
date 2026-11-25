package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaDetails

interface MangaCatalogue {
    suspend fun search(
        query: String,
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<MangaDetails>>

    suspend fun ranking(
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<Ranked<MangaDetails>>>
}
