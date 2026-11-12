package io.github.kauri_off.kiroku.title

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session

// What a screen already shows of a title, handed to the screen it opens, whose first frame is built from it.
class TitleSummary(
    val anime: Anime,
    val details: AnimeDetails?,
    val list: Map<AnimeId, ListEntry>,
    val airing: Map<AnimeId, Airing>,
    val session: Session?,
)
