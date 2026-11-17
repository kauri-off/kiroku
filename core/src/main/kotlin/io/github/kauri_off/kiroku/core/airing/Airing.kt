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
    // Non-null when the source doubts these numbers count the title, as when two entries of it each start at 1: the other's highest aired.
    val doubtedOver: Int? = null,
) {
    init {
        require(totalEpisodes == null || totalEpisodes > 0)
        require(doubtedOver == null || doubtedOver > 0)
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

// Why a count is not exact.
enum class Inexact {
    // Nothing is stored of the title's episodes.
    NoSchedule,

    // A4: the stored schedule numbers its episodes differently from MAL and is not used.
    NumberedDifferently,

    // A5: the schedule's total and MAL's differ by more than the one episode a recap explains.
    TotalsDisagree,

    // A5: the title still airs by MAL, and nothing has been scheduled for longer than a break lasts.
    ScheduleStopped,

    // The source doubts which of the title's numberings it gave, so the count is at least the other's.
    NumberingDoubted,
}

// What has aired of a title at an instant. Only :core builds one, and [airingAt] is the one way to from stored data.
@ConsistentCopyVisibility
data class Airing internal constructor(
    val aired: AiredCount,
    val total: Int?,
    val nextEpisode: ScheduledEpisode?,
    val latestAiredAt: Instant?,
    // Null exactly when the count is exact.
    val inexact: Inexact? = if (aired is AiredCount.Exact) null else Inexact.NoSchedule,
    // The same stored data gives this result until then; null when time alone changes nothing.
    val changesAt: Instant? = nextEpisode?.airsAt,
    // What is known of the premiere of a title that has aired nothing; null once an episode has aired.
    val premiere: Premiere? = if (nextEpisode != null && aired == AiredCount.Exact(0)) Premiere.At(nextEpisode.airsAt) else null,
) {
    init {
        require(total == null || total > 0)
        require((aired is AiredCount.Exact) == (inexact == null))
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
        // A wait on the result ends no later than its next episode.
        if (nextEpisode != null) require(changesAt != null && !changesAt.isAfter(nextEpisode.airsAt))
        if (premiere != null) require(aired !is AiredCount.Known || aired.value == 0)
        // An exact 0 is a stored first episode still to come, which is the premiere's time.
        require((aired == AiredCount.Exact(0)) == (premiere is Premiere.At))
        if (premiere is Premiere.At) require(premiere.instant == nextEpisode?.airsAt)
    }
}

// The status every rule of airing, notification and freshness reads: one the app does not know is read as airing.
val AiringStatus.effective: AiringStatus
    get() =
        when (this) {
            AiringStatus.NotYetAired, AiringStatus.Airing, AiringStatus.Finished -> this
            is AiringStatus.Unrecognized -> AiringStatus.Airing
        }

internal fun episodeTotal(
    anime: Anime,
    airing: Airing?,
): Int? = anime.malEpisodes ?: airing?.total

internal fun toWatch(
    entry: ListEntry,
    airing: Airing?,
): AiredCount.Known? = toWatch(entry.anime.airingStatus.effective, entry.values, airing)

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

// Only :core builds one, as every part of [TitleAiring].
sealed interface Premiere {
    @ConsistentCopyVisibility
    data class At internal constructor(
        val instant: Instant,
    ) : Premiere

    @ConsistentCopyVisibility
    data class OnDay internal constructor(
        val date: LocalDate,
    ) : Premiere

    @ConsistentCopyVisibility
    data class InMonth internal constructor(
        val yearMonth: YearMonth,
    ) : Premiere

    @ConsistentCopyVisibility
    data class InYear internal constructor(
        val year: Year,
    ) : Premiere

    data object Unannounced : Premiere
}

// [today] is Japan's day.
internal fun premiereOf(
    anime: Anime,
    airing: Airing?,
    today: LocalDate,
): Premiere? {
    // A stored first episode still to come is the premiere's time, also where MAL's status already says finished.
    airing?.nextEpisode?.takeIf { airing.aired == AiredCount.Exact(0) }?.let { return Premiere.At(it.airsAt) }
    val status = anime.airingStatus.effective
    if (status == AiringStatus.Finished) return null
    when (val aired = airing?.aired) {
        is AiredCount.Known -> if (aired.value > 0) return null
        AiredCount.Unknown, null -> if (status != AiringStatus.NotYetAired) return null
    }
    return when (val date = anime.startDate) {
        is PartialDate.Day -> if (date.date < today) Premiere.Unannounced else Premiere.OnDay(date.date)
        is PartialDate.Month ->
            if (date.yearMonth < YearMonth.from(today)) Premiere.Unannounced else Premiere.InMonth(date.yearMonth)
        is PartialDate.OnlyYear ->
            if (date.value < Year.from(today)) Premiere.Unannounced else Premiere.InYear(date.value)
        null -> Premiere.Unannounced
    }
}

// What a title's airing block and a list row's airing lines show; null in [TitleAiring] when the title shows none.
sealed interface AiringPhase {
    @ConsistentCopyVisibility
    data class NotYetAired internal constructor(
        val premiere: Premiere,
    ) : AiringPhase

    @ConsistentCopyVisibility
    data class Aired internal constructor(
        val aired: AiredCount.Known,
        // Present only when above zero.
        val toWatch: AiredCount.Known?,
        val next: ScheduledEpisode?,
    ) : AiringPhase

    data object CountUnknown : AiringPhase
}

// Takes the user's values, not their entry, so the airing status is read from [anime] alone.
internal fun airingPhase(
    anime: Anime,
    values: ListValues?,
    airing: Airing?,
    premiere: Premiere?,
): AiringPhase? {
    // A3: a title MAL marks finished shows no airing, whatever a stored schedule still holds.
    val status = anime.airingStatus.effective
    if (status == AiringStatus.Finished) return null
    premiere?.let { return AiringPhase.NotYetAired(it) }
    val aired = airing?.aired as? AiredCount.Known ?: return AiringPhase.CountUnknown
    val toWatch = values?.let { toWatch(status, it, airing) }?.takeIf { it.value > 0 }
    // An episode the user has already recorded as watched is not announced as next (W9).
    val next = airing.nextEpisode?.takeIf { values == null || it.number > values.watchedEpisodes }
    return AiringPhase.Aired(aired, toWatch, next)
}

// When the next episode airs, as the title shows it: the next episode, or a premiere whose time is known.
internal val AiringPhase.nextAirsAt: Instant?
    get() =
        when (this) {
            is AiringPhase.Aired -> next?.airsAt
            is AiringPhase.NotYetAired -> (premiere as? Premiere.At)?.instant
            AiringPhase.CountUnknown -> null
        }

// A title's place in "Up next", by what its row shows.
sealed interface UpNext {
    // Episodes to watch: the most recently aired first.
    @ConsistentCopyVisibility
    data class ToWatch internal constructor(
        val latestAiredAt: Instant?,
    ) : UpNext

    // A known time for the next episode or the premiere: the soonest first.
    @ConsistentCopyVisibility
    data class Coming internal constructor(
        val at: Instant,
    ) : UpNext

    data object Rest : UpNext
}

// The one result the List, the title page and the order read (A1). Only [titleAiring] builds it.
@ConsistentCopyVisibility
data class TitleAiring internal constructor(
    val airing: Airing?,
    val total: Int?,
    val phase: AiringPhase?,
    val upNext: UpNext,
)

// [airing] is null for a title whose airing was not computed: its count is not known, and its premiere is MAL's date as at [now].
fun titleAiring(
    anime: Anime,
    values: ListValues?,
    airing: Airing?,
    now: Instant,
): TitleAiring {
    val premiere = if (airing != null) airing.premiere else premiereOf(anime, null, japanDay(now))
    val phase = airingPhase(anime, values, airing, premiere)
    return TitleAiring(airing, episodeTotal(anime, airing), phase, upNext(phase, airing))
}

// The same from stored data: [values] are the user's, null for a title that is not on their list.
fun titleAiring(
    anime: Anime,
    values: ListValues?,
    schedule: AiringSchedule?,
    now: Instant,
): TitleAiring = titleAiring(anime, values, airingAt(anime, schedule, now), now)

// A title without a result has nothing to watch and no known time, so it is among the rest.
internal fun upNext(
    anime: Anime,
    values: ListValues?,
    airing: Airing?,
): UpNext = if (airing == null) UpNext.Rest else upNext(airingPhase(anime, values, airing, airing.premiere), airing)

private fun upNext(
    phase: AiringPhase?,
    airing: Airing?,
): UpNext {
    val coming = phase?.nextAirsAt
    return when {
        (phase as? AiringPhase.Aired)?.toWatch != null -> UpNext.ToWatch(airing?.latestAiredAt)
        coming != null -> UpNext.Coming(coming)
        else -> UpNext.Rest
    }
}

// MAL's dates are Japan's, so a day is over when it is over there.
internal fun japanDay(now: Instant): LocalDate = now.atZone(JAPAN).toLocalDate()

// Until when a title without a result reads the same as at [now]: the end of that day in Japan.
fun unchangedWithoutResultUntil(now: Instant): Instant = japanDay(now).plusDays(1).atStartOfDay(JAPAN).toInstant()
