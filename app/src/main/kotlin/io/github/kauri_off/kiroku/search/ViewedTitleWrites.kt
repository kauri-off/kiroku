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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ViewedTitleWrites(
    private val scope: CoroutineScope,
    private val post: (ShellMessage) -> Unit,
) {
    class Opening(
        val owner: RecentTitleScope,
        val key: RecentTitleId,
        val onDismissed: () -> Unit,
    ) {
        var removal: CompletableDeferred<Unit>? = null
        var dismissed = false
        val recording = Mutex()

        suspend fun awaitRemoval() {
            while (true) {
                val pending = removal ?: return
                pending.await()
            }
        }

        suspend fun record(write: suspend () -> Unit) {
            while (!dismissed) {
                awaitRemoval()
                recording.withLock {
                    if (dismissed) return
                    if (removal == null) {
                        write()
                        return
                    }
                }
            }
        }
    }

    private val openings = mutableSetOf<Opening>()
    private val removals = MutableStateFlow(emptySet<RecentTitleRemoval>())
    val pending = removals.asStateFlow()

    fun open(
        owner: RecentTitleScope,
        key: RecentTitleId,
        onDismissed: () -> Unit,
    ): Opening = Opening(owner, key, onDismissed).also { openings += it }

    fun close(opening: Opening) {
        openings -= opening
    }

    fun isRemoving(
        owner: RecentTitleScope,
        key: RecentTitleId,
    ): Boolean = removals.value.any { it.scope == owner && it.key == key }

    fun remove(
        history: RecentTitles,
        target: RecentTitleRemoval,
        restore: () -> Unit,
    ) = remove(history, target, restore, openings.filter { it.owner == target.scope && it.key == target.key })

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
        val removal = CompletableDeferred<Unit>()
        openings.forEach { it.removal = removal }
        removals.value += target
        scope.launch {
            try {
                openings.forEach { it.recording.withLock {} }
                when (val result = history.remove(target)) {
                    RecentTitleRemovalResult.Removed -> {
                        openings.forEach {
                            it.dismissed = true
                            it.onDismissed()
                        }
                    }
                    RecentTitleRemovalResult.Stale -> restore()
                    is RecentTitleRemovalResult.Failed -> {
                        restore()
                        val applicable = history.observeRemovalTarget(target)
                        val retry = target.copy(through = result.through ?: target.order)
                        post(
                            ShellMessage(
                                ShellMessage.Text.CouldNotRemoveViewedTitle,
                                ShellMessage.Action.RetryViewedTitle(
                                    target.copy(through = null),
                                    applicable,
                                ) { remove(history, retry, {}, openings) },
                            ),
                        )
                    }
                }
            } finally {
                openings.forEach { if (it.removal === removal) it.removal = null }
                removal.complete(Unit)
                removals.value -= target
            }
        }
    }
}
