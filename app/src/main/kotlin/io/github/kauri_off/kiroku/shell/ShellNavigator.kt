package io.github.kauri_off.kiroku.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import io.github.kauri_off.kiroku.notShown
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder

// One back stack per destination (ARCHITECTURE section 6.3); each stack starts at its destination's root.
class ShellNavigator(
    private val stacks: Map<Destination, MutableList<NavKey>>,
    private val selected: MutableState<Destination> = mutableStateOf(Destination.List),
    private val restartCount: MutableIntState = mutableIntStateOf(0),
) {
    init {
        require(Destination.entries.all { stacks[it]?.firstOrNull() == it.root }) { notShown("Every stack starts at its root") }
    }

    val current: Destination get() = selected.value

    // How the last change of screen went, for its transition.
    var way by mutableStateOf(Way.Across)
        private set

    val stack: List<NavKey> get() = stackOf(current)

    // Part of every screen's identity, so a restart keeps no screen's state, a root's included.
    val restarts: Int get() = restartCount.intValue

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

    // Shows [destination] with its stack popped to its root.
    fun home(destination: Destination) {
        way = if (destination == current) Way.Up else Way.Across
        selected.value = destination
        stacks.getValue(destination).popTo(1)
    }

    // Where a start without a session leaves the app: the List shown, every stack at its root, no screen's state kept.
    fun restart() {
        way = Way.Across
        selected.value = Destination.List
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

// Survives recreation and process death: the stacks are saved with their keys, the selection by name, the restarts by count.
@Composable
fun rememberShellNavigator(start: OpenedPage? = null): ShellNavigator {
    fun initial(destination: Destination) =
        NavBackStack(destination.root, *listOfNotNull(start?.takeIf { it.destination == destination }?.key).toTypedArray())
    val list = rememberSerializable(serializer = StackSerializer(ListRoot)) { initial(Destination.List) }
    val search = rememberSerializable(serializer = StackSerializer(SearchRoot)) { initial(Destination.Search) }
    val profile = rememberSerializable(serializer = StackSerializer(ProfileRoot)) { initial(Destination.Profile) }
    val selected = rememberSaveable(saver = destinationSaver) { mutableStateOf(start?.destination ?: Destination.List) }
    val restarts = rememberSaveable(saver = restartSaver) { mutableIntStateOf(0) }
    return remember(list, search, profile, selected, restarts) {
        val stacks = mapOf(Destination.List to list, Destination.Search to search, Destination.Profile to profile)
        stacks.values.forEach { stack -> stack.removeAll { it is NotBuiltKey } }
        ShellNavigator(stacks, selected, restarts)
    }
}

private val destinationSaver =
    Saver<MutableState<Destination>, Any>(
        save = { it.value.name },
        restore = { saved ->
            val value = if (saved is MutableState<*>) saved.value else saved
            Destination.entries.firstOrNull { it.name == value || it == value }?.let { mutableStateOf(it) }
        },
    )

private val restartSaver =
    Saver<MutableIntState, Any>(
        save = { it.intValue },
        restore = { saved ->
            val value = if (saved is MutableIntState) saved.intValue else saved as? Int
            value?.let { mutableIntStateOf(it) }
        },
    )

private val navSerializer = NavBackStackSerializer(NavKeySerializer<NavKey>())

private class StackSerializer(
    private val root: NavKey,
) : KSerializer<NavBackStack<NavKey>> by navSerializer {
    override fun deserialize(decoder: Decoder): NavBackStack<NavKey> =
        try {
            navSerializer.deserialize(decoder).takeIf { it.firstOrNull() == root } ?: NavBackStack(root)
        } catch (_: Exception) {
            NavBackStack(root)
        } catch (_: LinkageError) {
            NavBackStack(root)
        }
}
