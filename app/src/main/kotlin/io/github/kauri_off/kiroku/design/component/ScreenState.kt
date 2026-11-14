package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import io.github.kauri_off.kiroku.PATIENCE
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// The loading, empty and error states of every screen. Each fills the space it is given and centres itself in it;
// a message is announced when it appears.
sealed interface ScreenState {
    data object Loading : ScreenState

    data class Empty(
        val message: String,
        val action: StateAction? = null,
        // A second way on, as a text button under the action.
        val secondary: StateAction? = null,
    ) : ScreenState

    data class Error(
        val message: String,
        val onRetry: () -> Unit,
    ) : ScreenState
}

data class StateAction(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
fun ScreenState(
    state: ScreenState,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(horizontal = Spacing.l, vertical = Spacing.xl), contentAlignment = Alignment.Center) {
        when (state) {
            ScreenState.Loading -> {
                // Named and in its place at once; drawn once the wait outlasts the patience, timed on the main looper.
                var waited by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.Main) { delay(PATIENCE) }
                    waited = true
                }
                val loading = stringResource(R.string.design_loading)
                WaitIndicator(Modifier.semantics { contentDescription = loading }, shown = waited)
            }
            is ScreenState.Empty -> Message(state.message, state.action, state.secondary)
            is ScreenState.Error -> Message(state.message, StateAction(stringResource(R.string.design_retry), state.onRetry))
        }
    }
}

@Composable
private fun Message(
    message: String,
    action: StateAction?,
    secondary: StateAction? = null,
) {
    Column(
        Modifier
            .widthIn(max = Sizes.messageMaxWidth)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
            textAlign = TextAlign.Center,
        )
        if (action != null || secondary != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (action != null) Button(onClick = action.onClick, shapes = ButtonDefaults.shapes()) { Text(action.label) }
                if (secondary != null) TextButton(onClick = secondary.onClick, shapes = ButtonDefaults.shapes()) { Text(secondary.label) }
            }
        }
    }
}
