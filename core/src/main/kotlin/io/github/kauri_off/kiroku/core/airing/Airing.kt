package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.PartialDate
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

data class ScheduledEpisode(
    val number: Int,
    val airsAt: Instant,
) {
    init {
        require(number > 0)
    }
}

data class AiringSchedule(
    val episodes: List<ScheduledEpisode>,
    val totalEpisodes: Int?,
) {
    init {
        require(totalEpisodes == null || totalEpisodes > 0)
    }
}

sealed interface AiredCount {
    data object Unknown : AiredCount

    sealed interface Known : AiredCount {
        val value: Int
    }

    data class Exact(
        override val value: Int,
    ) : Known

    data class Estimated(
        override val value: Int,
    ) : Known
}

data class Airing(
    val aired: AiredCount,
    val total: Int?,
    val nextEpisode: ScheduledEpisode?,
    val latestAiredAt: Instant?,
) {
    init {
        require(total == null || total > 0)
        if (aired is AiredCount.Known) {
            require(aired.value >= 0)
            require(total == null || aired.value <= total)
            require(aired.value > 0 || latestAiredAt == null)
        } else {
            require(latestAiredAt == null)
        }
        if (nextEpisode != null) {
            require(aired is AiredCount.Exact)
            require(nextEpisode.number > aired.value)
            require(total == null || nextEpisode.number <= total)
        }
        if (aired == AiredCount.Exact(0)) require(nextEpisode?.number == 1)
    }
}

fun episodeTotal(
    anime: Anime,
    airing: Airing?,
): Int? = airing?.total ?: anime.malEpisodes

fun toWatch(
    entry: ListEntry,
    airing: Airing?,
): AiredCount.Known? {
    if (entry.status != ListStatus.Watching) return null
    if (entry.anime.airingStatus == AiringStatus.Finished) return null
    val aired = airing?.aired as? AiredCount.Known ?: return null
    val count = (aired.value - entry.values.watchedEpisodes).coerceAtLeast(0)
    return when (aired) {
        is AiredCount.Exact -> AiredCount.Exact(count)
        is AiredCount.Estimated -> AiredCount.Estimated(count)
    }
}

sealed interface Premiere {
    data class At(
        val instant: Instant,
    ) : Premiere

    data class OnDay(
        val date: LocalDate,
    ) : Premiere

    data class InMonth(
        val yearMonth: YearMonth,
    ) : Premiere

    data class InYear(
        val year: Year,
    ) : Premiere

    data object Unannounced : Premiere
}

fun premiereOf(
    anime: Anime,
    airing: Airing?,
): Premiere? {
    if (anime.airingStatus == AiringStatus.Finished) return null
    when (val aired = airing?.aired) {
        is AiredCount.Known -> if (aired.value > 0) return null
        AiredCount.Unknown, null -> if (anime.airingStatus != AiringStatus.NotYetAired) return null
    }
    airing?.nextEpisode?.takeIf { it.number == 1 }?.let { return Premiere.At(it.airsAt) }
    return when (val date = anime.startDate) {
        is PartialDate.Day -> Premiere.OnDay(date.date)
        is PartialDate.Month -> Premiere.InMonth(date.yearMonth)
        is PartialDate.OnlyYear -> Premiere.InYear(date.value)
        null -> Premiere.Unannounced
    }
}
