package io.github.kauri_off.kiroku.list

internal class ListFilter {
    private val filter =
        TitleFilter(
            rows = { content: ListContent.Tabs -> content.statuses.flatMap { content.pages.getValue(it).rows } },
            id = ListRow::id,
            title = { row, _ -> row.title },
        )

    fun rows(
        content: ListContent,
        text: String?,
        session: Int = 0,
    ): List<ListRow>? = filter.rows(content as? ListContent.Tabs, text, session)
}

internal class TitleFilter<Source : Any, Row, Id>(
    private val rows: (Source) -> List<Row>,
    private val id: (Row) -> Id,
    private val title: (Row, Source) -> String,
) {
    private val positions = LinkedHashSet<Id>()
    private var source: Source? = null
    private var ordered = emptyList<Row>()
    private var query: String? = null
    private var session = 0
    private var matches = emptyList<Row>()

    fun rows(
        content: Source?,
        text: String?,
        session: Int,
    ): List<Row>? {
        if (text == null || content == null) {
            positions.clear()
            source = null
            ordered = emptyList()
            query = null
            matches = emptyList()
            return null
        }
        if (this.session != session) {
            positions.clear()
            source = null
            this.session = session
        }
        if (source !== content) {
            val byId = rows(content).associateBy(id)
            positions.addAll(byId.keys)
            ordered = positions.mapNotNull(byId::get)
        }
        if (source !== content || query != text) {
            matches = if (text.isEmpty()) ordered else ordered.filter { title(it, content).contains(text, ignoreCase = true) }
            source = content
            query = text
        }
        return matches
    }
}
