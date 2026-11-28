package io.github.kauri_off.kiroku.search

import io.github.kauri_off.kiroku.core.repository.RecentTitleId
import io.github.kauri_off.kiroku.core.repository.RecentTitleRemoval
import io.github.kauri_off.kiroku.core.repository.RecentTitleRemovalResult
import io.github.kauri_off.kiroku.core.repository.RecentTitleScope
import io.github.kauri_off.kiroku.core.repository.RecentTitles
import io.github.kauri_off.kiroku.shell.ShellMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class ViewedTitleWrites(
    private val scope: CoroutineScope,
    private val post: (ShellMessage) -> Unit,
) {
    private class Opening(
        val owner: RecentTitleScope,
        val key: RecentTitleId,
        var removal: CompletableDeferred<Boolean>? = null,
        var dismissed: Boolean = false,
    )

    private val reservations = mutableListOf<Opening>()
    private val removals = MutableStateFlow(emptySet<RecentTitleRemoval>())
    val pending = removals.asStateFlow()

    suspend fun reserve(
        history: RecentTitles,
        owner: RecentTitleScope,
        key: RecentTitleId,
    ): Long? {
        val opening = Opening(owner, key)
        reservations += opening
        try {
            val order = history.reserveVisit(owner)
            while (!opening.dismissed) {
                val removal = opening.removal ?: return order
                if (removal.await()) return null
                if (opening.removal === removal) return order
            }
            return null
        } finally {
            reservations.remove(opening)
        }
    }

    fun isRemoving(
        owner: RecentTitleScope,
        key: RecentTitleId,
    ): Boolean = removals.value.any { it.scope == owner && it.key == key }

    fun remove(
        history: RecentTitles,
        target: RecentTitleRemoval,
        restore: () -> Unit,
    ) = remove(history, target, restore, reservations.filter { it.owner == target.scope && it.key == target.key })

    private fun remove(
        history: RecentTitles,
        target: RecentTitleRemoval,
        restore: () -> Unit,
        openings: List<Opening>,
    ) {
        if (isRemoving(target.scope, target.key)) {
            restore()
            return
        }
        val removal = CompletableDeferred<Boolean>()
        openings.forEach { it.removal = removal }
        removals.value += target
        scope.launch {
            try {
                when (val result = history.remove(target)) {
                    RecentTitleRemovalResult.Removed -> {
                        openings.forEach { it.dismissed = true }
                        removal.complete(true)
                    }
                    RecentTitleRemovalResult.Stale -> restore()
                    is RecentTitleRemovalResult.Failed -> {
                        restore()
                        val applicable = history.observe(target.scope).map { visits -> visits.any(target::matches) }
                        val retry = target.copy(through = result.through ?: target.order)
                        post(
                            ShellMessage(
                                ShellMessage.Text.CouldNotRemoveViewedTitle,
                                ShellMessage.Action.RetryViewedTitle(applicable) { remove(history, retry, {}, openings) },
                            ),
                        )
                    }
                }
            } finally {
                removal.complete(false)
                removals.value -= target
            }
        }
    }
}
