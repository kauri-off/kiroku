package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.airingPhase
import io.github.kauri_off.kiroku.core.airing.nextAirsAt
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.PartialDate
import java.time.Instant
import java.time.LocalDate

enum class ListSort { UpNext, Title, LastUpdated, Score, StartDate }

enum class SortDirection { Ascending, Descending }

// Each sort's own order, which is the default; "Up next" is a grouping, not a scale, so it has no direction.
val ListSort.defaultDirection: SortDirection?
    get() =
        when (this) {
            ListSort.UpNext -> null
            ListSort.Title, ListSort.StartDate -> SortDirection.Ascending
            ListSort.LastUpdated, ListSort.Score -> SortDirection.Descending
        }

// The sorts a tab offers; "Up next" needs airing data, which another user's list does not have.
fun sortsFor(
    status: ListStatus,
    withAiring: Boolean,
): List<ListSort> = ListSort.entries.filter { it != ListSort.UpNext || (status == ListStatus.Watching && withAiring) }

fun defaultSort(
    status: ListStatus,
    withAiring: Boolean,
): ListSort = if (ListSort.UpNext in sortsFor(status, withAiring)) ListSort.UpNext else ListSort.LastUpdated

// Orders entries once, when the app starts, a tab's sort or direction changes or the list is refreshed. An entry without
// the key goes last in both directions; equal keys go by title, A to Z.
fun order(
    entries: List<ListEntry>,
    sort: ListSort,
    airing: (AnimeId) -> Airing?,
    today: LocalDate,
    direction: SortDirection? = sort.defaultDirection,
    title: (ListEntry) -> String,
): List<ListEntry> {
    val byTitle = compareBy<ListEntry, String>(String.CASE_INSENSITIVE_ORDER) { title(it) }
    val ascending = direction != SortDirection.Descending
    val comparator: Comparator<ListEntry> =
        when (sort) {
            ListSort.UpNext -> upNext(airing, today)
            ListSort.Title -> if (ascending) byTitle else byTitle.reversed()
            ListSort.LastUpdated -> keyed(ascending) { it.updatedAt }
            ListSort.Score -> keyed(ascending) { it.values.score }
            ListSort.StartDate -> keyed(ascending) { it.anime.startDate?.firstDay() }
        }
    return entries.sortedWith(comparator.then(byTitle).thenBy { it.anime.id.value })
}

private fun <K : Comparable<K>> keyed(
    ascending: Boolean,
    key: (ListEntry) -> K?,
): Comparator<ListEntry> = compareBy(nullsLast(if (ascending) naturalOrder() else reverseOrder())) { key(it) }

// "Up next", grouped by what the row shows, from the same airing phase as the title page: episodes to watch, latest
// aired first; then a known time for the next episode or the premiere, soonest first; then the rest, last updated first.
fun upNextGroup(phase: AiringPhase?): Int =
    when {
        (phase as? AiringPhase.Aired)?.toWatch != null -> 0
        phase?.nextAirsAt != null -> 1
        else -> 2
    }

private fun upNext(
    airing: (AnimeId) -> Airing?,
    today: LocalDate,
): Comparator<ListEntry> {
    fun phase(entry: ListEntry) = airingPhase(entry.anime, entry.values, airing(entry.anime.id), today)
    return compareBy<ListEntry> { upNextGroup(phase(it)) }.then { a, b ->
        val pa = phase(a)
        when (upNextGroup(pa)) {
            0 ->
                nullsLast(reverseOrder<Instant>())
                    .compare(airing(a.anime.id)?.latestAiredAt, airing(b.anime.id)?.latestAiredAt)
            1 -> pa!!.nextAirsAt!!.compareTo(phase(b)!!.nextAirsAt!!)
            else -> b.updatedAt.compareTo(a.updatedAt)
        }
    }
}

private fun PartialDate.firstDay(): LocalDate =
    when (this) {
        is PartialDate.Day -> date
        is PartialDate.Month -> yearMonth.atDay(1)
        is PartialDate.OnlyYear -> value.atDay(1)
    }
