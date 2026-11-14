package io.github.kauri_off.kiroku

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// How a route reads a view model's state: only while its screen is started.
@Composable
fun <T> StateFlow<T>.collectAsScreenState(): State<T> = collectAsStateWithLifecycle()

@Composable
fun <T> Flow<T>.collectAsScreenState(initial: T): State<T> = collectAsStateWithLifecycle(initial)

// A screen counts as shown while it is on top and started: one that a back gesture uncovers is not shown until it ends.
@Composable
fun CountAsShown(shown: StateFlow<Unit>) {
    if (LocalOnTop.current) shown.collectAsStateWithLifecycle()
}
