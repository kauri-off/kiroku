package io.github.kauri_off.kiroku.core.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// S1: opens MAL's authorisation page in the user's browser; false when nothing on the device took it.
fun interface SignInBrowser {
    fun open(address: String): Boolean
}

// Why a sign-in failed; none of them names a credential.
enum class SignInFailure {
    // This build or data source cannot sign in.
    Unavailable,

    // The device's store failed: the started sign-in, its end or the tokens were not written.
    NotStored,

    // Nothing opened MAL's page.
    NoBrowser,

    // Not this build's redirect, or one that cannot be read.
    Unreadable,

    // No sign-in was waiting for a redirect.
    Unasked,

    // The sign-in was started too long ago.
    Old,

    // The redirect does not carry the sign-in's state.
    WrongState,

    // MAL's redirect carries no code.
    Denied,

    // MAL rejected the code.
    Rejected,

    // MAL's answer to the code did not come or holds no usable tokens.
    NoTokens,
}

// Where the sign-in stands; one that ended stays until it is acknowledged.
sealed interface SignInStep {
    // No sign-in, or one that waits in the browser.
    data object None : SignInStep

    // A redirect arrived and is being checked or exchanged for tokens.
    data object Completing : SignInStep

    data class Failed(
        val reason: SignInFailure,
    ) : SignInStep

    data object SignedIn : SignInStep
}

// The sign-in with MAL (S1 to S4), which outlives every screen.
interface SignIn {
    val step: StateFlow<SignInStep>

    // Starts a sign-in and opens its page; an earlier failure is forgotten.
    suspend fun start(browser: SignInBrowser)

    // What the browser handed back; anything but the answer to the sign-in that waits fails (S3).
    fun redirected(uri: String)

    // Forgets [step] when it is still the sign-in's end.
    fun acknowledge(step: SignInStep)
}

// A data source with no MAL account behind it: nothing starts and every redirect fails.
class NoSignIn(
    private val onFailure: ((SignInFailure) -> Unit)? = null,
) : SignIn {
    private val state = MutableStateFlow<SignInStep>(SignInStep.None)
    override val step = state.asStateFlow()

    override suspend fun start(browser: SignInBrowser) = failed()

    override fun redirected(uri: String) = failed()

    override fun acknowledge(step: SignInStep) {
        state.compareAndSet(step, SignInStep.None)
    }

    private fun failed() {
        state.value = SignInStep.Failed(SignInFailure.Unavailable)
        onFailure?.invoke(SignInFailure.Unavailable)
    }
}
