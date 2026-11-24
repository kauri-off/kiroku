package io.github.kauri_off.kiroku.shell

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.titlesub.SubScreen
import io.github.kauri_off.kiroku.titlesub.SubScreenBody

@Composable
fun NotBuiltScreen(
    title: String,
    onBack: () -> Unit,
    snackbars: SnackbarHostState,
) {
    SubScreen(
        title = title,
        sessionExpired = false,
        onBack = onBack,
        onSignIn = {},
        snackbars = snackbars,
        body = SubScreenBody.State(ScreenState.Empty(stringResource(R.string.shell_not_in_this_build))),
    )
}
