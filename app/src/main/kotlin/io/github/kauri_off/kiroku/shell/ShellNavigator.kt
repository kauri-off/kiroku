package io.github.kauri_off.kiroku.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.SavedState
import androidx.savedstate.serialization.encodeToSavedState
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.list.ListMode
import io.github.kauri_off.kiroku.manga.MangaListKey
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.search.BrowseKey
import io.github.kauri_off.kiroku.title.TitleKey

// One back stack per destination (ARCHITECTURE section 6.3); each stack starts at its destination's root.
class ShellNavigator internal constructor(
    private val stacks: Map<Destination, MutableList<NavKey>>,
    private val selected: MutableState<Destination> = mutableStateOf(Destination.List),
    private val restartCount: MutableIntState = mutableIntStateOf(0),
    private val mode: MutableState<ListMode> = mutableStateOf(ListMode.Anime),
) {
    internal var listMode by mode

    init {
        require(Destination.entries.all { stacks[it]?.firstOrNull() == it.root }) { notShown("Every stack starts at its root") }
        if (stacks.getValue(Destination.List).removeAll { it == MangaListKey }) listMode = ListMode.Manga
    }

    val current: Destination get() = selected.value

    // How the last change of screen went, for its transition.
    var way by mutableStateOf(Way.Across)
        private set

    val stack: List<NavKey> get() = stackOf(current)

    // Part of every screen's identity, so a restart keeps no screen's state, a root's included.
    val restarts: Int get() = restartCount.intValue

    internal var hadSession: Boolean? = null
    internal var sessionOwner: SignInId? = null
    internal var restoredNavigation = false

    fun stackOf(destination: Destination): List<NavKey> = stacks.getValue(destination)

    // Tapping the current destination again pops it to its root.
    fun select(destination: Destination) {
        if (destination == current) {
            stacks.getValue(destination).popTo(1)
            way = Way.Up
        } else {
            selected.value = destination
            way = Way.Across
        }
    }

    fun open(key: NavKey) {
        stacks.getValue(current).add(key)
        way = Way.Deeper
    }

    // Takes [key] off every stack that holds it; a root stays.
    fun remove(key: NavKey) {
        if (backStack.last() == key) way = Way.Up
        stacks.values.forEach { stack -> (stack.lastIndex downTo 1).filter { stack[it] == key }.forEach { stack.removeAt(it) } }
    }

    internal fun pruneSuggestions(owner: SignInId?) {
        val search = stacks.getValue(Destination.Search)
        val retired =
            search.indexOfFirst { key ->
                when {
                    key is BrowseKey && key.list == BrowseList.Suggestions -> owner == null || key.owner != owner.value
                    key is TitleKey && key.suggestionsOwner != null -> key.suggestionsOwner != owner?.value
                    else -> false
                }
            }
        if (retired < 0) return
        if (current == Destination.Search) way = Way.Up
        search.popTo(retired)
    }

    // Shows [destination] with its stack popped to its root.
    fun home(destination: Destination) {
        way = if (destination == current) Way.Up else Way.Across
        selected.value = destination
        stacks.getValue(destination).popTo(1)
    }

    fun restart() {
        way = Way.Across
        selected.value = Destination.List
        listMode = ListMode.Anime
        stacks.values.forEach { it.popTo(1) }
        restartCount.intValue++
    }

    // Back pops the current stack; from the root of Search or Profile it goes to List. False: leave the app.
    fun back(): Boolean {
        val stack = stacks.getValue(current)
        return when {
            stack.size > 1 -> {
                stack.popTo(stack.size - 1)
                way = Way.Up
                true
            }
            current != Destination.List -> {
                selected.value = Destination.List
                way = Way.Across
                true
            }
            else -> false
        }
    }

    // The stacks Back walks through, top last: the List's, then the current destination's when that is not List.
    val visible: List<Destination>
        get() = if (current == Destination.List) listOf(Destination.List) else listOf(Destination.List, current)

    val backStack: List<NavKey> get() = visible.flatMap { stackOf(it) }

    private fun MutableList<NavKey>.popTo(size: Int) {
        while (this.size > size) removeAt(lastIndex)
    }

    companion object {
        fun inMemory(): ShellNavigator = ShellNavigator(Destination.entries.associateWith { mutableListOf(it.root) })
    }
}

// A page over its destination's root, with the List alone beneath.
data class OpenedPage(
    val destination: Destination,
    val key: NavKey,
)

@Composable
fun rememberShellNavigator(start: OpenedPage? = null): ShellNavigator = rememberShellNavigator(start, null)

@Composable
internal fun rememberShellNavigator(
    start: OpenedPage?,
    activityNavigation: ActivityNavigation?,
    startup: Destination = Destination.List,
): ShellNavigator {
    fun initial(destination: Destination) =
        NavBackStack(destination.root, *listOfNotNull(start?.takeIf { it.destination == destination }?.key).toTypedArray())
    val saveWithCompose = activityNavigation == null
    var restoredStack = false
    val restored = { restoredStack = true }
    val list = rememberSaveable(saver = stackSaver(ListRoot, saveWithCompose, restored)) { initial(Destination.List) }
    val search = rememberSaveable(saver = stackSaver(SearchRoot, saveWithCompose, restored)) { initial(Destination.Search) }
    val profile = rememberSaveable(saver = stackSaver(ProfileRoot, saveWithCompose, restored)) { initial(Destination.Profile) }
    val selected =
        rememberSaveable(saver = destinationSaver(saveWithCompose)) { mutableStateOf(start?.destination ?: startup) }
    val restarts = rememberSaveable(saver = restartSaver(saveWithCompose)) { mutableIntStateOf(0) }
    val mode = rememberSaveable(saver = listModeSaver(saveWithCompose)) { mutableStateOf(ListMode.Anime) }
    val navigator =
        remember(list, search, profile, selected, restarts, mode, activityNavigation) {
            activityNavigation?.restored ?: ShellNavigator(
                mapOf(Destination.List to list, Destination.Search to search, Destination.Profile to profile),
                selected,
                restarts,
                mode,
            ).apply { restoredNavigation = restoredStack }
        }
    SideEffect { activityNavigation?.navigator = navigator }
    return navigator
}

private fun destinationSaver(save: Boolean) =
    Saver<MutableState<Destination>, Any>(
        save = { it.value.name.takeIf { save } },
        restore = { saved ->
            val value = if (saved is MutableState<*>) saved.value else saved
            Destination.entries.firstOrNull { it.name == value || it == value }?.let { mutableStateOf(it) }
        },
    )

private fun listModeSaver(save: Boolean) =
    Saver<MutableState<ListMode>, String>(
        save = { it.value.name.takeIf { _ -> save && it.value != ListMode.Anime } },
        restore = { listModeOf(it)?.let { mode -> mutableStateOf(mode) } },
    )

private fun restartSaver(save: Boolean) =
    Saver<MutableIntState, Any>(
        save = { it.intValue.takeIf { save } },
        restore = { saved ->
            val value = if (saved is MutableIntState) saved.intValue else saved as? Int
            value?.let { mutableIntStateOf(it) }
        },
    )

private fun stackSaver(
    root: NavKey,
    save: Boolean,
    restored: () -> Unit,
) = Saver<NavBackStack<NavKey>, SavedState>(
    save = { if (save) encodeToSavedState(navSerializer, it) else null },
    restore = { decodeStack(it, root).also { restored() } },
)
