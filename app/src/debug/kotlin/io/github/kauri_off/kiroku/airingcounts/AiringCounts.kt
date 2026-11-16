package io.github.kauri_off.kiroku.airingcounts

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.Inexact
import io.github.kauri_off.kiroku.core.airing.tracked
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import java.time.Instant

// How a tracked title's aired count is known, by the rule of section 4 that decided it.
enum class CountKind { Exact, NumberedDifferently, Doubt, NoSchedule, Unknown }

// What the stored data says of a count that is not exact, beyond its kind.
sealed interface Why {
    data class NumberedFrom(
        val first: Int,
        val last: Int,
    ) : Why

    data class TotalsDiffer(
        val mal: Int,
        val schedule: Int,
    ) : Why

    data class StoppedAfter(
        val lastAired: Instant,
    ) : Why
}

data class CountedTitle(
    val id: AnimeId,
    val title: String,
    val kind: CountKind,
    val aired: AiredCount,
    // Null when the stored schedule is not there to say more.
    val why: Why?,
)

data class AiringCounts(
    val counts: Map<CountKind, Int>,
    // By kind, then by title.
    val notExact: List<CountedTitle>,
) {
    val tracked: Int get() = counts.values.sum()
}

// A tracked title with no result yet is shown as a count unknown, and is counted so.
fun airingCounts(
    entries: List<ListEntry>,
    airing: Map<AnimeId, Airing>,
    schedules: Map<AnimeId, AiringSchedule>,
    language: TitleLanguage,
): AiringCounts {
    val titles =
        entries.filter { it.tracked }.map { entry ->
            val anime = entry.anime
            val result = airing[anime.id]
            val kind = kindOf(result)
            CountedTitle(
                anime.id,
                anime.titles.preferred(language),
                kind,
                result?.aired ?: AiredCount.Unknown,
                why(result, schedules[anime.id], anime.malEpisodes),
            )
        }
    return AiringCounts(
        CountKind.entries.associateWith { kind -> titles.count { it.kind == kind } },
        titles.filter { it.kind != CountKind.Exact }.sortedWith(compareBy<CountedTitle> { it.kind }.thenBy { it.title }),
    )
}

private fun kindOf(airing: Airing?): CountKind =
    when (airing?.inexact) {
        null -> if (airing == null) CountKind.Unknown else CountKind.Exact
        Inexact.NumberedDifferently -> CountKind.NumberedDifferently
        Inexact.TotalsDisagree, Inexact.ScheduleStopped -> CountKind.Doubt
        Inexact.NoSchedule -> if (airing.aired is AiredCount.Known) CountKind.NoSchedule else CountKind.Unknown
    }

private fun why(
    airing: Airing?,
    schedule: AiringSchedule?,
    malTotal: Int?,
): Why? {
    val numbers = schedule?.episodes?.map { it.number }.orEmpty()
    return when (airing?.inexact) {
        Inexact.NumberedDifferently -> if (numbers.isEmpty()) null else Why.NumberedFrom(numbers.min(), numbers.max())
        Inexact.TotalsDisagree -> {
            val total = maxOf(schedule?.totalEpisodes ?: 0, numbers.maxOrNull() ?: 0)
            if (malTotal == null || total == 0) null else Why.TotalsDiffer(malTotal, total)
        }
        Inexact.ScheduleStopped -> airing.latestAiredAt?.let(Why::StoppedAfter)
        Inexact.NoSchedule, null -> null
    }
}

sealed interface AiringCountsState {
    data object Reading : AiringCountsState

    data object Unreadable : AiringCountsState

    data class Read(
        val counts: AiringCounts,
    ) : AiringCountsState
}

sealed interface AiringCountsEvent {
    data object Retry : AiringCountsEvent
}

// Reads what is stored and asks no source; a list or a store that cannot be read ends it as unreadable.
fun airingCountsState(
    list: ListSource,
    airing: AiringRepository,
    language: TitleLanguage,
): Flow<AiringCountsState> =
    combine(list.observeEntries(), airing.observeAiring(), airing.observeSchedules()) { entries, results, schedules ->
        AiringCountsState.Read(airingCounts(entries, results, schedules, language)) as AiringCountsState
    }.catch { if (it is DataException) emit(AiringCountsState.Unreadable) else throw it }
