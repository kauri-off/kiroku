package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.RecentTitle
import io.github.kauri_off.kiroku.core.repository.RecentTitleScope
import io.github.kauri_off.kiroku.core.repository.RecentTitles
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.UUID

internal class DemoRecentTitles(
    private val session: SessionRepository,
) : RecentTitles {
    private data class History(
        val scope: RecentTitleScope?,
        val visits: List<Pair<Long, RecentTitle>> = emptyList(),
    )

    private val history = MutableStateFlow(History(null))
    private var sequence = 0L
    private val guest = MutableStateFlow(UUID.randomUUID().toString())

    override fun observeScope() =
        combine(session.observeSession(), guest) { session, guest ->
            RecentTitleScope(
                session.owner,
                session.owner?.value ?: guest,
            )
        }

    override fun observe(scope: RecentTitleScope) =
        combine(observeScope(), history) { current, kept ->
            if (current == scope && kept.scope == scope) kept.visits.map { it.second } else emptyList()
        }

    override suspend fun reserveVisit(scope: RecentTitleScope): Long? = if (observeScope().first() == scope) ++sequence else null

    override suspend fun record(
        scope: RecentTitleScope,
        title: RecentTitle,
        visit: Long,
    ) {
        if (observeScope().first() != scope) return
        sequence = maxOf(sequence, visit)
        val old = history.value.takeIf { it.scope == scope } ?: History(scope)
        val previous = old.visits.firstOrNull { it.second.key == title.key }
        if (previous != null && previous.first > visit) return
        val safe = title.copy(safeForSearch = title.safeForSearch ?: previous?.second?.safeForSearch ?: true)
        val entries = (listOf(visit to safe) + old.visits.filterNot { it.second.key == title.key }).sortedByDescending { it.first }
        history.value = old.copy(visits = entries.take(RecentTitles.LIMIT))
    }

    fun clear() {
        history.value = History(null)
        guest.value = UUID.randomUUID().toString()
    }
}
