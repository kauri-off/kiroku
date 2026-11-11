package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
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
): Int? = anime.malEpisodes ?: airing?.total

fun toWatch(
    entry: ListEntry,
    airing: Airing?,
): AiredCount.Known? = toWatch(entry.anime.airingStatus, entry.values, airing)

private fun toWatch(
    airingStatus: AiringStatus,
    values: ListValues,
    airing: Airing?,
): AiredCount.Known? {
    if (values.status != ListStatus.Watching) return null
    if (airingStatus == AiringStatus.Finished) return null
    val aired = airing?.aired as? AiredCount.Known ?: return null
    val count = (aired.value - values.watchedEpisodes).coerceAtLeast(0)
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
    today: LocalDate,
): Premiere? {
    if (anime.airingStatus == AiringStatus.Finished) return null
    when (val aired = airing?.aired) {
        is AiredCount.Known -> if (aired.value > 0) return null
        AiredCount.Unknown, null -> if (anime.airingStatus != AiringStatus.NotYetAired) return null
    }
    airing?.nextEpisode?.takeIf { it.number == 1 }?.let { return Premiere.At(it.airsAt) }
    return when (val date = anime.startDate) {
        is PartialDate.Day -> if (date.date < today) Premiere.Unannounced else Premiere.OnDay(date.date)
        is PartialDate.Month ->
            if (date.yearMonth < YearMonth.from(today)) Premiere.Unannounced else Premiere.InMonth(date.yearMonth)
        is PartialDate.OnlyYear ->
            if (date.value < Year.from(today)) Premiere.Unannounced else Premiere.InYear(date.value)
        null -> Premiere.Unannounced
    }
}

// What a title's airing block and a list row's airing lines show, the one decision both screens read; null when the
// title shows none (A1).
sealed interface AiringPhase {
    data class NotYetAired(
        val premiere: Premiere,
    ) : AiringPhase

    data class Aired(
        val aired: AiredCount.Known,
        // Present only when above zero.
        val toWatch: AiredCount.Known?,
        val next: ScheduledEpisode?,
    ) : AiringPhase

    data object CountUnknown : AiringPhase
}

// Takes the user's values, not their entry, so the airing status is read from [anime] alone.
fun airingPhase(
    anime: Anime,
    values: ListValues?,
    airing: Airing?,
    today: LocalDate,
): AiringPhase? {
    premiereOf(anime, airing, today)?.let { return AiringPhase.NotYetAired(it) }
    if (anime.airingStatus == AiringStatus.Finished) return null
    val aired = airing?.aired as? AiredCount.Known ?: return AiringPhase.CountUnknown
    val toWatch = values?.let { toWatch(anime.airingStatus, it, airing) }?.takeIf { it.value > 0 }
    return AiringPhase.Aired(aired, toWatch, airing.nextEpisode)
}

// When the next episode airs, as the title shows it: the next episode, or a premiere whose time is known.
val AiringPhase.nextAirsAt: Instant?
    get() =
        when (this) {
            is AiringPhase.Aired -> next?.airsAt
            is AiringPhase.NotYetAired -> (premiere as? Premiere.At)?.instant
            AiringPhase.CountUnknown -> null
        }
