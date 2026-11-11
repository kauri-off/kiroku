package io.github.kauri_off.kiroku.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import io.github.kauri_off.kiroku.notShown

// One back stack per destination (ARCHITECTURE section 6.3); each stack starts at its destination's root.
class ShellNavigator(
    private val stacks: Map<Destination, MutableList<NavKey>>,
    private val selected: MutableState<Destination> = mutableStateOf(Destination.List),
) {
    init {
        require(Destination.entries.all { stacks[it]?.firstOrNull() == it.root }) { notShown("Every stack starts at its root") }
    }

    val current: Destination get() = selected.value

    val stack: List<NavKey> get() = stackOf(current)

    fun stackOf(destination: Destination): List<NavKey> = stacks.getValue(destination)

    // Tapping the current destination again pops it to its root.
    fun select(destination: Destination) {
        if (destination == current) {
            stacks.getValue(destination).popTo(1)
        } else {
            selected.value = destination
        }
    }

    fun open(key: NavKey) {
        stacks.getValue(current).add(key)
    }

    // Back pops the current stack; from the root of Search or Profile it goes to List. False: leave the app.
    fun back(): Boolean {
        val stack = stacks.getValue(current)
        return when {
            stack.size > 1 -> {
                stack.popTo(stack.size - 1)
                true
            }
            current != Destination.List -> {
                selected.value = Destination.List
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

// Survives recreation and process death: the stacks are saved with their keys, the selection by name.
@Composable
fun rememberShellNavigator(): ShellNavigator {
    val list = rememberNavBackStack(Destination.List.root)
    val search = rememberNavBackStack(Destination.Search.root)
    val profile = rememberNavBackStack(Destination.Profile.root)
    val selected = rememberSaveable { mutableStateOf(Destination.List) }
    return remember(list, search, profile, selected) {
        ShellNavigator(
            mapOf(Destination.List to list, Destination.Search to search, Destination.Profile to profile),
            selected,
        )
    }
}
