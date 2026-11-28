package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.RecentTitle
import io.github.kauri_off.kiroku.core.repository.RecentTitleId
import io.github.kauri_off.kiroku.core.repository.RecentTitleScope
import io.github.kauri_off.kiroku.core.repository.RecentTitles
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal class DemoRecentTitles(
    private val session: SessionRepository,
) : RecentTitles {
    private data class History(
        val scope: RecentTitleScope?,
        val visits: List<Pair<Long, RecentTitle>> = emptyList(),
        val dismissed: Map<RecentTitleId, Long> = emptyMap(),
        val evictedThrough: Long = 0,
    )

    private val gate = Mutex()
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

    override suspend fun reserveVisit(scope: RecentTitleScope): Long? =
        gate.withLock { if (observeScope().first() == scope) ++sequence else null }

    override suspend fun remove(
        scope: RecentTitleScope,
        key: RecentTitleId,
    ): Boolean =
        gate.withLock {
            if (observeScope().first() != scope) return@withLock false
            val old = history.value.takeIf { it.scope == scope } ?: History(scope)
            history.value =
                old.copy(
                    visits = old.visits.filterNot { it.second.key == key },
                    dismissed = old.dismissed + (key to sequence),
                )
            true
        }

    override suspend fun record(
        scope: RecentTitleScope,
        title: RecentTitle,
        visit: Long,
    ) {
        gate.withLock {
            if (observeScope().first() != scope) return
            sequence = maxOf(sequence, visit)
            val old = history.value.takeIf { it.scope == scope } ?: History(scope)
            val dismissed = old.dismissed[title.key]
            if (dismissed != null && visit <= dismissed) return
            val previous = old.visits.firstOrNull { it.second.key == title.key }
            val minimum = previous?.first ?: (old.evictedThrough + 1)
            if (visit < minimum) return
            val safe = title.copy(safeForSearch = title.safeForSearch ?: previous?.second?.safeForSearch ?: true)
            val entries = (listOf(visit to safe) + old.visits.filterNot { it.second.key == title.key }).sortedByDescending { it.first }
            val overflow = entries.getOrNull(RecentTitles.LIMIT)?.first ?: 0
            history.value =
                old.copy(
                    visits = entries.take(RecentTitles.LIMIT),
                    evictedThrough = maxOf(old.evictedThrough, overflow),
                )
        }
    }

    suspend fun clear() {
        gate.withLock {
            history.value = History(null)
            guest.value = UUID.randomUUID().toString()
        }
    }
}
