package io.github.kauri_off.kiroku.signin

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.notShown

// What each state puts in the sentence's place, and whether it can start a sign-in.
private class Form(
    @param:StringRes val sentence: Int,
    val canSignIn: Boolean,
    val announced: Boolean = false,
    val isError: Boolean = false,
)

private fun SignInState.form(): Form =
    when (this) {
        SignInState.Ready -> Form(R.string.signin_sentence, canSignIn = true)
        SignInState.NoClientId -> Form(R.string.signin_no_client_id, canSignIn = false)
        SignInState.Completing -> Form(R.string.signin_signing_in, canSignIn = false, announced = true)
        SignInState.Failed -> Form(R.string.signin_failed, canSignIn = true, announced = true, isError = true)
    }

// Back and the app's name in the top app bar; the mark, the sentence and both buttons in the middle; the notice at the foot.
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = { BackIcon(onEvent) },
            )
        },
        snackbarHost = { ScreenSnackbarHost(snackbars) },
    ) { padding ->
        Body(state, onEvent, Modifier.padding(padding))
    }
}

@Composable
private fun BackIcon(onEvent: (SignInEvent) -> Unit) {
    IconButton(onClick = { onEvent(SignInEvent.Back) }) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
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
        Layout(
            content = {
                Mark()
                Choices(state.form(), onEvent)
                Notice()
            },
            modifier =
                Modifier
                    .widthIn(max = Sizes.contentMaxWidth)
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.l, vertical = Spacing.xl),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val (mark, choices, notice) = measurables.map { it.measure(loose) }
            val markGap = Spacing.l.roundToPx()
            val noticeGap = Spacing.xxl.roundToPx()
            val rest = choices.height + noticeGap + notice.height
            // The mark is the first to give way when the window has no room for it.
            val withMark = rest + markGap + mark.height <= constraints.minHeight
            val group = choices.height + if (withMark) mark.height + markGap else 0
            val height = maxOf(constraints.minHeight, rest)
            val width = constraints.maxWidth
            layout(width, height) {
                var y = (height - group - noticeGap - notice.height) / 2
                if (withMark) {
                    mark.placeRelative((width - mark.width) / 2, y)
                    y += mark.height + markGap
                }
                choices.placeRelative((width - choices.width) / 2, y)
                notice.placeRelative((width - notice.width) / 2, height - notice.height)
            }
        }
    }
}

// The launcher icon's foreground, whose circle spans the mark's bounds; decorative, so a screen reader skips it.
@Composable
private fun Mark() {
    Image(
        painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier =
            Modifier
                .size(Sizes.signInMark)
                .testTag(SignInTags.mark)
                .graphicsLayer {
                    scaleX = MARK_ART_SCALE
                    scaleY = MARK_ART_SCALE
                },
    )
}

internal object SignInTags {
    val mark = notShown("signin-mark")
}

// The foreground's outer circle is 52 of its 108 units wide.
private const val MARK_ART_SCALE = 108f / 52f

@Composable
private fun Choices(
    form: Form,
    onEvent: (SignInEvent) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Sentence(form)
        Button(
            onClick = { onEvent(SignInEvent.SignIn) },
            enabled = form.canSignIn,
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

// The one sentence: why to sign in, why this build cannot, that a sign-in is completing, or that it failed.
@Composable
private fun Sentence(form: Form) {
    Text(
        stringResource(form.sentence),
        style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
        color = if (form.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .widthIn(max = Sizes.messageMaxWidth)
                .semantics { if (form.announced) liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Notice() {
    Text(
        stringResource(R.string.signin_unofficial),
        style = MaterialTheme.typography.labelMedium.copy(lineBreak = LineBreak.Heading),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = Sizes.messageMaxWidth),
    )
}

private val PrimaryButtonHeight = ButtonDefaults.MediumContainerHeight
