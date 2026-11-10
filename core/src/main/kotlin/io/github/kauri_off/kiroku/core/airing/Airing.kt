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
)

data class AiringSchedule(
    val episodes: List<ScheduledEpisode>,
    val totalEpisodes: Int?,
)

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
        if (aired is AiredCount.Known) {
            require(aired.value >= 0)
            require(total == null || aired.value <= total)
        }
        require(nextEpisode == null || aired is AiredCount.Exact)
    }
}

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
    if (anime.airingStatus != AiringStatus.NotYetAired) return null
    val first = airing?.nextEpisode?.takeIf { it.number == 1 }
    if (first != null) return Premiere.At(first.airsAt)
    return when (val date = anime.startDate) {
        is PartialDate.Day -> Premiere.OnDay(date.date)
        is PartialDate.Month -> Premiere.InMonth(date.yearMonth)
        is PartialDate.OnlyYear -> Premiere.InYear(date.value)
        null -> Premiere.Unannounced
    }
}
