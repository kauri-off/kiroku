package io.github.kauri_off.kiroku

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// How a route reads a view model's state: only while its screen is started, and on the main thread at once, so a
// value an event produced is applied before the event returns and a text field never reads a stale one.
@Composable
fun <T> StateFlow<T>.collectAsScreenState(): State<T> = collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)

@Composable
fun <T> Flow<T>.collectAsScreenState(initial: T): State<T> = collectAsStateWithLifecycle(initial, context = Dispatchers.Main.immediate)
