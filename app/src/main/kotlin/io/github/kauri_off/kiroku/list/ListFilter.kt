package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.model.AnimeId

internal class ListFilter {
    private val positions = LinkedHashSet<AnimeId>()
    private var source: ListContent.Tabs? = null
    private var ordered = emptyList<ListRow>()
    private var query: String? = null
    private var matches = emptyList<ListRow>()

    fun rows(
        content: ListContent,
        text: String?,
    ): List<ListRow>? {
        if (text == null || content !is ListContent.Tabs) {
            positions.clear()
            source = null
            ordered = emptyList()
            query = null
            matches = emptyList()
            return null
        }
        if (source !== content) {
            val byId = content.statuses.flatMap { content.pages.getValue(it).rows }.associateBy { it.id }
            positions.addAll(byId.keys)
            ordered = positions.mapNotNull(byId::get)
        }
        if (source !== content || query != text) {
            matches = if (text.isEmpty()) ordered else ordered.filter { it.title.contains(text, ignoreCase = true) }
            source = content
            query = text
        }
        return matches
    }
}
