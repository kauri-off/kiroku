package io.github.kauri_off.kiroku.core.statistics

import io.github.kauri_off.kiroku.core.model.Counted
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.StatusCounts
import io.github.kauri_off.kiroku.core.model.UserStatistics
import java.time.Duration

// The statistics of a list's entries, whoever's list it is and however it was read.
fun statisticsOf(entries: Collection<ListEntry>): UserStatistics {
    val scores = entries.mapNotNull { it.values.score }
    return UserStatistics(
        titleCount = entries.size,
        statuses = StatusCounts.of(entries.map { it.status }),
        daysWatched = entries.fold(Duration.ZERO) { sum, entry -> sum + entry.timeWatched }.toMillis() / DAY_MILLIS,
        episodesWatched = entries.sumOf { it.episodesWatched },
        meanScore = if (scores.isEmpty()) null else scores.sum().toDouble() / scores.size,
        scores = scores.groupingBy { it }.eachCount().toSortedMap(),
        genres = genresOf(entries),
        mediaTypes =
            entries
                .mapNotNull { it.anime.mediaType }
                .groupingBy { it }
                .eachCount()
                .map { (type, count) -> Counted(type, count) }
                .sortedWith(compareByDescending<Counted<MediaType>> { it.count }.thenBy { it.value.order }.thenBy { it.value.code }),
        years =
            entries
                .mapNotNull { it.anime.startDate?.year }
                .groupingBy { it }
                .eachCount()
                .toSortedMap(),
    )
}

private const val DAY_MILLIS = 86_400_000.0

// As MAL counts them: the watched episodes, and each rewatch as all the title's episodes; none where their number is unknown.
private val ListEntry.episodesWatched: Int get() = values.watchedEpisodes + values.timesRewatched * (anime.malEpisodes ?: 0)

// The episodes the entry counts, each of the title's average length; nothing where the length is not known.
private val ListEntry.timeWatched: Duration
    get() = anime.averageEpisodeDuration?.multipliedBy(episodesWatched.toLong()) ?: Duration.ZERO

// A genre is one by its id, named as the first entry that has it names it, and counted once per title.
private fun genresOf(entries: Collection<ListEntry>): List<Counted<Genre>> =
    entries
        .flatMap { entry ->
            entry.anime.genres
                .orEmpty()
                .distinctBy { it.id }
        }.groupBy { it.id }
        .map { (_, same) -> Counted(same.first(), same.size) }
        .sortedWith(compareByDescending<Counted<Genre>> { it.count }.thenBy { it.value.id })

// The order of media types that tie: TV first, promotional videos last, then those the app does not know.
private val MediaType.order: Int
    get() =
        when (this) {
            MediaType.Tv -> 0
            MediaType.TvSpecial -> 1
            MediaType.Movie -> 2
            MediaType.Ova -> 3
            MediaType.Ona -> 4
            MediaType.Special -> 5
            MediaType.Music -> 6
            MediaType.Commercial -> 7
            MediaType.PromotionalVideo -> 8
            is MediaType.Unrecognized -> 9
        }

private val MediaType.code: String get() = (this as? MediaType.Unrecognized)?.malCode.orEmpty()
