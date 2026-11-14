package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.Ranked
import io.github.kauri_off.kiroku.core.repository.SearchRepository

// Search and the browse lists of a live source, which answer nothing yet.
internal fun liveSearch(): SearchRepository =
    object : SearchRepository {
        private val unavailable = Outcome.Failure(DataError.ServerError)

        override suspend fun search(
            query: String,
            offset: Int,
            includeAdult: Boolean,
        ): Outcome<Page<Anime>> = unavailable

        override suspend fun browse(
            list: BrowseList,
            offset: Int,
            includeAdult: Boolean,
        ): Outcome<Page<Ranked<Anime>>> = unavailable
    }
