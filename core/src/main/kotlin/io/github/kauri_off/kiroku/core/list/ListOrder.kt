package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.toWatch
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.PartialDate
import java.time.Instant
import java.time.LocalDate

enum class ListSort { UpNext, Title, LastUpdated, Score, StartDate }

// The sorts a tab offers; "Up next" needs airing data, which another user's list does not have.
fun sortsFor(
    status: ListStatus,
    withAiring: Boolean,
): List<ListSort> = ListSort.entries.filter { it != ListSort.UpNext || (status == ListStatus.Watching && withAiring) }

fun defaultSort(
    status: ListStatus,
    withAiring: Boolean,
): ListSort = if (ListSort.UpNext in sortsFor(status, withAiring)) ListSort.UpNext else ListSort.LastUpdated

// Orders entries once, when a tab is shown, its sort changes or the list is refreshed; equal keys go by title.
fun order(
    entries: List<ListEntry>,
    sort: ListSort,
    airing: (AnimeId) -> Airing?,
    title: (ListEntry) -> String,
): List<ListEntry> {
    val byTitle = compareBy<ListEntry, String>(String.CASE_INSENSITIVE_ORDER) { title(it) }
    val comparator: Comparator<ListEntry> =
        when (sort) {
            ListSort.UpNext -> upNext(airing)
            ListSort.Title -> byTitle
            ListSort.LastUpdated -> compareByDescending { it.updatedAt }
            ListSort.Score -> compareBy(nullsLast(reverseOrder())) { it.values.score }
            ListSort.StartDate -> compareBy(nullsLast()) { it.anime.startDate?.firstDay() }
        }
    return entries.sortedWith(comparator.then(byTitle))
}

// "Up next": titles with episodes to watch, latest aired first; then a known next episode, soonest first; then the
// rest, last updated first.
private fun upNext(airing: (AnimeId) -> Airing?): Comparator<ListEntry> {
    fun group(entry: ListEntry): Int {
        val airing = airing(entry.anime.id)
        return when {
            (toWatch(entry, airing)?.value ?: 0) > 0 -> 0
            airing?.nextEpisode != null -> 1
            else -> 2
        }
    }
    return Comparator { a, b ->
        val ga = group(a)
        val gb = group(b)
        if (ga != gb) return@Comparator ga.compareTo(gb)
        when (ga) {
            0 ->
                nullsLast(reverseOrder<Instant>())
                    .compare(airing(a.anime.id)?.latestAiredAt, airing(b.anime.id)?.latestAiredAt)
            1 -> airing(a.anime.id)!!.nextEpisode!!.airsAt.compareTo(airing(b.anime.id)!!.nextEpisode!!.airsAt)
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
