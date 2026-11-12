package io.github.kauri_off.kiroku.signin

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.screenshots.shown

class SigninScreenshots : ScreenshotRegistry() {
    override val content = contentCase { Screen(SignInState.Ready) }
    override val loading = shown { Screen(SignInState.Completing) }
    override val empty = notShown("The sign-in screen shows no data, so it has nothing to be empty of.")
    override val error = shown { Screen(SignInState.Failed) }

    override val cases = listOf(case("no-client-id") { Screen(SignInState.NoClientId) })
}

@Composable
private fun Screen(state: SignInState) = SignInScreen(state, onEvent = {}, snackbars = remember { SnackbarHostState() })
