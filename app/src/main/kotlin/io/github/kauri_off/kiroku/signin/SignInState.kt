package io.github.kauri_off.kiroku.signin

// What the sign-in screen shows (ARCHITECTURE 9.2).
sealed interface SignInState {
    data object Ready : SignInState

    // A build without a MAL client id cannot sign in (ARCHITECTURE 3.3).
    data object NoClientId : SignInState

    // Demo data has no MAL account behind it, whatever the build's client id.
    data object NoAccount : SignInState

    // The browser has handed the sign-in back and its tokens are being fetched.
    data object Completing : SignInState

    data object Failed : SignInState
}

sealed interface SignInEvent {
    data object SignIn : SignInEvent

    data object Browse : SignInEvent

    data object Back : SignInEvent
}
