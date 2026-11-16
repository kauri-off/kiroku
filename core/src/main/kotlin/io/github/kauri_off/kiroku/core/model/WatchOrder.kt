package io.github.kauri_off.kiroku.core.model

// The title and at most five others: an opened page asks MAL for each of them in turn, and six rows fill about half of
// the first screen. A longer chain shows the titles nearest to the page's own, and goes on from the page of its last.
const val CHAIN_LIMIT = 6

// The chain of sequels around one title, as far as the pages read so far tell it.
data class SequelChain(
    // In the order to watch them, the title itself included.
    val order: List<Anime>,
    // The order in which the walk reads their pages: the title, then the nearest titles, a sequel before a prequel.
    val walk: List<AnimeId>,
    // Every title's page is read and none names a sequel or a prequel outside the chain, so each place is final.
    val whole: Boolean,
) {
    // A title with no sequel and no prequel has no chain.
    val shown: Boolean get() = order.size > 1
}

// [pages] holds the related titles of each title whose page is read.
fun sequelChain(
    title: Anime,
    pages: Map<AnimeId, List<RelatedAnime>>,
): SequelChain {
    val walk = chainWalk(title.id, pages)
    val named = mutableMapOf(title.id to title)
    for (id in walk) pages[id].orEmpty().forEach { named.putIfAbsent(it.anime.id, it.anime) }
    val members = walk.toSet()
    val whole = walk.all { id -> pages[id]?.let { related -> nextInWalk(related).all { it.id in members } } == true }
    return SequelChain(watchOrder(walk.associateWith(named::getValue), pages, walk), walk, whole)
}

// The titles of the chain in the order the walk reads their pages. Only sequels and prequels are followed. The walk
// goes on from a title once its page is read and stops at the first one that is not, so reading that page never
// changes the titles the walk took before it.
fun chainWalk(
    start: AnimeId,
    pages: Map<AnimeId, List<RelatedAnime>>,
): List<AnimeId> {
    val walk = linkedSetOf(start)
    val queue = ArrayDeque(listOf(start))
    walk@ while (queue.isNotEmpty()) {
        val related = pages[queue.removeFirst()] ?: break
        for (next in nextInWalk(related)) {
            if (walk.size == CHAIN_LIMIT) break@walk
            if (walk.add(next.id)) queue.addLast(next.id)
        }
    }
    return walk.toList()
}

private fun nextInWalk(related: List<RelatedAnime>): List<Anime> =
    listOf(RelationType.Sequel, RelationType.Prequel).flatMap { relation ->
        related
            .filter { it.relation == relation }
            .map { it.anime }
            .sortedWith(byStart)
    }

// Every title after the ones its pages and theirs put before it; of those free to go next, the one that starts first.
// A loop in MAL's relations is broken at its earliest title.
private fun watchOrder(
    members: Map<AnimeId, Anime>,
    pages: Map<AnimeId, List<RelatedAnime>>,
    walk: List<AnimeId>,
): List<Anime> {
    val before = members.keys.associateWith { mutableSetOf<AnimeId>() }
    for (id in members.keys) {
        for (related in pages[id].orEmpty()) {
            val other = related.anime.id
            if (other == id || other !in members) continue
            when (related.relation) {
                RelationType.Sequel -> before.getValue(other) += id
                RelationType.Prequel -> before.getValue(id) += other
                else -> Unit
            }
        }
    }
    val first = compareBy(byStart) { id: AnimeId -> members.getValue(id) }.thenBy { walk.indexOf(it) }
    val placed = LinkedHashSet<AnimeId>()
    while (placed.size < members.size) {
        val left = members.keys.filter { it !in placed }
        placed += left.filter { placed.containsAll(before.getValue(it)) }.minWithOrNull(first) ?: left.minWith(first)
    }
    return placed.map(members::getValue)
}

private val byStart: Comparator<Anime> = compareBy(nullsLast()) { it: Anime -> it.startDate?.let(::sortKey) }
