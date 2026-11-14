package io.github.kauri_off.kiroku.shell

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.titlesub.SubScreen
import io.github.kauri_off.kiroku.titlesub.SubScreenBody
import kotlinx.serialization.Serializable

// A screen a control leads to that a later milestone builds.
enum class NotBuilt { SignIn, Recommendations }

@Serializable
data class NotBuiltKey(
    val screen: NotBuilt,
) : NavKey

// Its name and Back, and the shared empty state saying it is not in this build.
@Composable
fun NotBuiltScreen(
    screen: NotBuilt,
    onBack: () -> Unit,
    snackbars: SnackbarHostState,
) {
    val title =
        when (screen) {
            NotBuilt.SignIn -> R.string.design_sign_in
            NotBuilt.Recommendations -> R.string.title_recommendations
        }
    NotBuiltScreen(stringResource(title), onBack, snackbars)
}

// A not-built screen named by the caller.
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
