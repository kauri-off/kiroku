package io.github.kauri_off.kiroku.core.model

import java.time.Instant
import java.time.LocalDate

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

    fun withWatched(
        watched: Int,
        malEpisodes: Int?,
        today: LocalDate,
    ): ListValues {
        require(watched >= 0)
        val todayDate = PartialDate.Day(today)
        return when {
            malEpisodes != null && watched >= malEpisodes && status != ListStatus.Completed ->
                copy(
                    status = ListStatus.Completed,
                    watchedEpisodes = watched,
                    startDate = if (status == ListStatus.Planned) startDate ?: todayDate else startDate,
                    finishDate = finishDate ?: todayDate,
                )
            status == ListStatus.Planned && watched > 0 ->
                copy(status = ListStatus.Watching, watchedEpisodes = watched, startDate = startDate ?: todayDate)
            else -> copy(watchedEpisodes = watched)
        }
    }

    fun markWatched(
        episode: Int,
        malEpisodes: Int?,
        today: LocalDate,
    ): ListValues = if (episode <= watchedEpisodes) this else withWatched(episode, malEpisodes, today)
}
