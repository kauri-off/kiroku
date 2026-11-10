package io.github.kauri_off.kiroku.core.model

import java.time.Instant

enum class ListStatus { Watching, Planned, Completed, OnHold, Dropped }

data class ListEntry(
    val anime: Anime,
    val values: ListValues,
    val updatedAt: Instant,
) {
    val status: ListStatus get() = values.status
}

data class ListValues(
    val status: ListStatus,
    val watchedEpisodes: Int,
    val score: Int?,
    val startDate: PartialDate?,
    val finishDate: PartialDate?,
) {
    init {
        require(watchedEpisodes >= 0)
        require(score == null || score in 1..10)
    }
}
