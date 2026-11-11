package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Spacing

// The loading, empty and error states of every screen. Each fills the space it is given and centres itself in it.
sealed interface ScreenState {
    data object Loading : ScreenState

    data class Empty(
        val message: String,
        val action: StateAction? = null,
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
                val loading = stringResource(R.string.design_loading)
                CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
            }
            is ScreenState.Empty -> Message(state.message, state.action)
            is ScreenState.Error -> Message(state.message, StateAction(stringResource(R.string.design_retry), state.onRetry))
        }
    }
}

@Composable
private fun Message(
    message: String,
    action: StateAction?,
) {
    Column(
        Modifier.widthIn(max = MessageWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (action != null) Button(onClick = action.onClick) { Text(action.label) }
    }
}

// Keeps a message of a few words in one or two balanced lines on wide windows.
private val MessageWidth = 320.dp
