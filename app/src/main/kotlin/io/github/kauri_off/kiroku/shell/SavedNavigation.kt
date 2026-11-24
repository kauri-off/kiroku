package io.github.kauri_off.kiroku.shell

import android.os.Bundle
import android.os.Parcelable
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
