package io.github.kauri_off.kiroku.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing

// The app's name in the top app bar; the sentence and both buttons in the middle; the unofficial notice at the foot.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    state: SignInState,
    onEvent: (SignInEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { ScreenSnackbarHost(snackbars) },
    ) { padding ->
        if (state == SignInState.Completing) {
            ScreenState(ScreenState.Loading, Modifier.padding(padding))
        } else {
            Body(state, onEvent, Modifier.padding(padding))
        }
    }
}

// Scrolls when large text makes it taller than the window; otherwise the middle group is centred above the notice.
@Composable
private fun Body(
    state: SignInState,
    onEvent: (SignInEvent) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = Sizes.contentMaxWidth)
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(Modifier)
            Choices(state, onEvent)
            Notice(Modifier.padding(top = Spacing.xxl))
        }
    }
}

@Composable
private fun Choices(
    state: SignInState,
    onEvent: (SignInEvent) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Sentence(state)
        Button(
            onClick = { onEvent(SignInEvent.SignIn) },
            enabled = state != SignInState.NoClientId,
            shapes = ButtonDefaults.shapesFor(PrimaryButtonHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(PrimaryButtonHeight),
            modifier =
                Modifier
                    .padding(top = Spacing.xl)
                    .heightIn(min = PrimaryButtonHeight)
                    .fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.signin_sign_in),
                style = ButtonDefaults.textStyleFor(PrimaryButtonHeight),
                textAlign = TextAlign.Center,
            )
        }
        TextButton(
            onClick = { onEvent(SignInEvent.Browse) },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.padding(top = Spacing.s),
        ) {
            Text(stringResource(R.string.signin_browse), textAlign = TextAlign.Center)
        }
    }
}

// The one sentence: why to sign in, why this build cannot, or that the last attempt failed.
@Composable
private fun Sentence(state: SignInState) {
    val failed = state == SignInState.Failed
    val text =
        when (state) {
            SignInState.NoClientId -> R.string.signin_no_client_id
            SignInState.Failed -> R.string.signin_failed
            else -> R.string.signin_sentence
        }
    Box(Modifier.widthIn(max = Sizes.messageMaxWidth).semantics { if (failed) liveRegion = LiveRegionMode.Polite }) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Notice(modifier: Modifier) {
    Text(
        stringResource(R.string.signin_unofficial),
        style = MaterialTheme.typography.labelMedium.copy(lineBreak = LineBreak.Heading),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.widthIn(max = Sizes.messageMaxWidth),
    )
}

private val PrimaryButtonHeight = ButtonDefaults.MediumContainerHeight
