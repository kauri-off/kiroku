package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.SignInId

interface MangaListReader {
    suspend fun stored(owner: SignInId): Outcome<MangaListSnapshot?>

    // Null when the screen stopped being shown before the read could start.
    suspend fun refresh(
        owner: SignInId,
        canDispatch: () -> Boolean = { true },
    ): Outcome<MangaListSnapshot>?
}
