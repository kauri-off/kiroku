package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.UpNext
import io.github.kauri_off.kiroku.core.airing.titleAiring
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.firstDay
import java.text.CollationKey
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

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
    status: EntryStatus,
    withAiring: Boolean,
): List<ListSort> = ListSort.entries.filter { it != ListSort.UpNext || (status == ListStatus.Watching && withAiring) }

fun defaultSort(
    status: EntryStatus,
    withAiring: Boolean,
): ListSort = if (ListSort.UpNext in sortsFor(status, withAiring)) ListSort.UpNext else ListSort.LastUpdated

// A tab's sort and its direction, which "Up next" has none of.
data class TabSort(
    val sort: ListSort,
    val direction: SortDirection? = sort.defaultDirection,
) {
    init {
        require((direction == null) == (sort.defaultDirection == null))
    }

    companion object {
        // A tab of the user's own list where nothing was chosen.
        fun defaultFor(status: EntryStatus): TabSort = TabSort(defaultSort(status, withAiring = true))
    }
}

// A missing key goes last in both directions; equal keys go by title A to Z, then by id.
fun order(
    entries: List<ListEntry>,
    sort: ListSort,
    airing: (AnimeId) -> Airing?,
    today: LocalDate,
    direction: SortDirection? = sort.defaultDirection,
    title: (ListEntry) -> String,
): List<ListEntry> {
    val titles = titleKeys(entries, title)
    val byTitle = compareBy<ListEntry> { titles.getValue(it.anime.id) }
    val ascending = direction != SortDirection.Descending
    val comparator: Comparator<ListEntry> =
        when (sort) {
            ListSort.UpNext -> upNext(entries, airing, today)
            ListSort.Title -> if (ascending) byTitle else byTitle.reversed()
            ListSort.LastUpdated -> keyed(ascending) { it.updatedAt }
            ListSort.Score -> keyed(ascending) { it.values.score }
            ListSort.StartDate -> keyed(ascending) { it.anime.startDate?.firstDay() }
        }
    return entries.sortedWith(comparator.then(byTitle).thenBy { it.anime.id.value })
}

// Root Unicode collation, case ignored: a letter with a macron or accent sorts with its base letter, in any language.
private fun titleKeys(
    entries: List<ListEntry>,
    title: (ListEntry) -> String,
): Map<AnimeId, CollationKey> {
    val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }
    return entries.associate { it.anime.id to collator.getCollationKey(title(it)) }
}

private fun <K : Comparable<K>> keyed(
    ascending: Boolean,
    key: (ListEntry) -> K?,
): Comparator<ListEntry> = compareBy(nullsLast(if (ascending) naturalOrder() else reverseOrder())) { key(it) }

// "Up next" by the keys of the result the row shows: episodes to watch, latest aired first; then a known time for the
// next episode or the premiere, soonest first; then the rest, last updated first.
private fun upNext(
    entries: List<ListEntry>,
    airing: (AnimeId) -> Airing?,
    today: LocalDate,
): Comparator<ListEntry> {
    val keys = entries.associate { it.anime.id to titleAiring(it.anime, it.values, airing(it.anime.id), today).upNext }
    return compareBy<ListEntry> { keys.getValue(it.anime.id).group }.then { a, b ->
        val other = keys.getValue(b.anime.id)
        when (val key = keys.getValue(a.anime.id)) {
            is UpNext.ToWatch ->
                nullsLast(reverseOrder<Instant>()).compare(key.latestAiredAt, (other as UpNext.ToWatch).latestAiredAt)
            is UpNext.Coming -> key.at.compareTo((other as UpNext.Coming).at)
            UpNext.Rest -> b.updatedAt.compareTo(a.updatedAt)
        }
    }
}

private val UpNext.group: Int
    get() =
        when (this) {
            is UpNext.ToWatch -> 0
            is UpNext.Coming -> 1
            UpNext.Rest -> 2
        }
