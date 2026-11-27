package io.github.kauri_off.kiroku.shell

import android.os.Bundle
import android.os.Parcelable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.list.ListMode
import io.github.kauri_off.kiroku.notShown

private val registryKey = notShown("androidx.lifecycle.BundlableSavedStateRegistry.key")
private val composePrefix = notShown("SaveableStateRegistry:")

internal fun discardUnreadableComposeState(activityState: Bundle?) {
    val registry = activityState?.getBundle(registryKey) ?: return
    for (provider in registry.keySet().filter { it.startsWith(composePrefix) }) {
        try {
            val compose = registry.getBundle(provider) ?: continue
            for (entry in compose.keySet().toList()) {
                try {
                    @Suppress("DEPRECATION")
                    compose.getParcelableArrayList<Parcelable>(entry)
                } catch (_: RuntimeException) {
                    compose.remove(entry)
                }
            }
        } catch (_: RuntimeException) {
            registry.remove(provider)
        }
    }
}

internal val navSerializer = NavBackStackSerializer(NavKeySerializer<NavKey>())

internal fun decodeStack(
    saved: Bundle?,
    root: NavKey,
): NavBackStack<NavKey> {
    if (saved == null) return NavBackStack(root)
    return try {
        val stack = decodeFromSavedState(navSerializer, saved)
        if (stack.firstOrNull() != root) return NavBackStack(root)
        stack.apply { removeAll { it is NotBuiltKey } }
    } catch (_: Exception) {
        NavBackStack(root)
    } catch (_: LinkageError) {
        NavBackStack(root)
    }
}

private val navigationKey = notShown("kiroku.shell.navigation")
private val selectionKey = notShown("selected")
private val restartsKey = notShown("restarts")
private val listModeKey = notShown("listMode")
private val hadSessionKey = notShown("hadSession")
private val sessionOwnerKey = notShown("sessionOwner")

internal fun listModeOf(name: String?): ListMode? = ListMode.entries.firstOrNull { it.name == name }

internal class ActivityNavigation(
    registry: SavedStateRegistry,
) {
    val restored: ShellNavigator? =
        registry.consumeRestoredStateForKey(navigationKey)?.takeIf { it.containsKey(selectionKey) }?.let { saved ->
            val stacks = Destination.entries.associateWith { decodeStack(saved.getBundle(it.name), it.root) }
            val selected = Destination.entries.firstOrNull { it.name == saved.getString(selectionKey) } ?: Destination.List
            ShellNavigator(stacks, mutableStateOf(selected), mutableIntStateOf(saved.getInt(restartsKey))).apply {
                if (saved.containsKey(hadSessionKey)) hadSession = saved.getBoolean(hadSessionKey)
                sessionOwner = saved.getString(sessionOwnerKey)?.let(::SignInId)
                listModeOf(saved.getString(listModeKey))?.let { listMode = it }
                restoredNavigation = true
            }
        }

    var navigator by mutableStateOf(restored)

    init {
        registry.registerSavedStateProvider(navigationKey) {
            Bundle().apply {
                navigator?.let { current ->
                    putString(selectionKey, current.current.name)
                    putInt(restartsKey, current.restarts)
                    if (current.listMode != ListMode.Anime) putString(listModeKey, current.listMode.name)
                    current.hadSession?.let { putBoolean(hadSessionKey, it) }
                    current.sessionOwner?.let { putString(sessionOwnerKey, it.value) }
                    for (destination in Destination.entries) {
                        val stack = NavBackStack(*current.stackOf(destination).toTypedArray())
                        putBundle(destination.name, encodeToSavedState(navSerializer, stack))
                    }
                }
            }
        }
    }
}
