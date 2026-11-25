package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaDetails

interface MangaCatalogue {
    suspend fun search(
        query: String,
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<MangaDetails>>

    // Null when the route deferred dispatch.
    suspend fun ranking(
        offset: Int,
        includeAdult: Boolean,
        canDispatch: () -> Boolean = { true },
    ): Outcome<Page<Ranked<MangaDetails>>>?
}
