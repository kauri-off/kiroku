package io.github.kauri_off.kiroku.manga

import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.list.orderManga
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage

internal val defaultMangaSort = TabSort(ListSort.LastUpdated)

internal class MangaListOrdering {
    private data class Ordered(
        val choice: TabSort,
        val ids: LinkedHashSet<MangaId>,
    )

    private val orders = mutableMapOf<MangaReadingStatus, Ordered>()
    private var language: TitleLanguage? = null
    private var lastPass = 0

    fun entries(
        displayed: List<MangaListEntry>,
        language: TitleLanguage,
        sorts: Map<MangaReadingStatus, TabSort>,
        orderPass: Int,
        stored: List<MangaListEntry>,
        edit: MangaEditingState?,
    ): List<MangaListEntry> {
        val reorder = this.language != language || orderPass != lastPass
        val hasPull = orderPass != lastPass
        this.language = language
        lastPass = orderPass
        if (reorder) orders.clear()
        val groups = displayed.groupBy(MangaListEntry::status)
        val newGroups =
            if (hasPull) stored.map { edit?.displayed(it) ?: it }.groupBy(MangaListEntry::status) else emptyMap()
        return (groups.keys + newGroups.keys).flatMap { status ->
            val rows = groups[status].orEmpty()
            val choice = sorts[status] ?: defaultMangaSort
            val ranked = orders[status]
            val ids =
                if (ranked == null || ranked.choice != choice) {
                    val source = newGroups[status] ?: rows
                    val ordered = LinkedHashSet<MangaId>()
                    if (reorder || ranked != null) orderManga(source, choice, language).mapTo(ordered, MangaListEntry::id)
                    orders[status] = Ordered(choice, ordered)
                    ordered
                } else {
                    ranked.ids
                }
            val byId = rows.associateBy(MangaListEntry::id)
            ids.addAll(byId.keys)
            ids.mapNotNull(byId::get)
        }
    }
}
