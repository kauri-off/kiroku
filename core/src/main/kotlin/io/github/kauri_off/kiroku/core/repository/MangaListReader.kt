package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.SignInId

interface MangaListReader {
    suspend fun stored(owner: SignInId): Outcome<MangaListSnapshot?>

    suspend fun refresh(owner: SignInId): Outcome<MangaListSnapshot>
}
