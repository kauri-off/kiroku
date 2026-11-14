package io.github.kauri_off.kiroku.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.SignIn
import io.github.kauri_off.kiroku.core.repository.SignInBrowser
import io.github.kauri_off.kiroku.core.repository.SignInStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// The sign-in screen; [account] is false for data with no MAL account behind it, the demo.
class SignInViewModel(
    private val signIn: SignIn,
    session: SessionRepository,
    private val account: Boolean,
) : ViewModel() {
    val state: StateFlow<SignInState> = signIn.step.map(::stateOf).stateIn(viewModelScope, WhileShown, stateOf(signIn.step.value))

    // The session was signed in while the screen showed, by this sign-in or by one a process before it completed.
    val signedIn: StateFlow<Boolean> =
        session.observeSession().map { it is Session.SignedIn }.stateIn(viewModelScope, WhileShown, false)

    // Called from the route's own scope: it holds the activity until the browser is open.
    suspend fun start(browser: SignInBrowser) = signIn.start(browser)

    private fun stateOf(step: SignInStep): SignInState =
        when {
            !account -> SignInState.NoAccount
            !signIn.canStart -> SignInState.NoClientId
            step == SignInStep.None -> SignInState.Ready
            step is SignInStep.Failed -> SignInState.Failed
            // Signed in: the route is about to leave for the List.
            else -> SignInState.Completing
        }

    // A failure shown on the screen is read once the screen is left.
    override fun onCleared() {
        (signIn.step.value as? SignInStep.Failed)?.let(signIn::acknowledge)
    }
}

// What the sign-in asks of the app until the app says it acted on it.
sealed interface SignInTurn {
    // A redirect arrived: its sign-in screen belongs on top.
    data object Redirected : SignInTurn

    // The sign-in ended signed in: the app goes to the List.
    data object SignedIn : SignInTurn
}

// The app's side of the sign-in, for as long as the app is shown: what it must act on, until it acknowledges it.
class SignInHandOver(
    private val signIn: SignIn,
) : ViewModel() {
    private val redirected = MutableStateFlow(false)

    // The step the last turn was decided from; it outlives the screen's collection, so a step is acted on once.
    private var last: SignInStep = SignInStep.None

    val turn: StateFlow<SignInTurn?> =
        combine(signIn.step.map(::arrived), redirected) { step, redirected ->
            when {
                step == SignInStep.SignedIn -> SignInTurn.SignedIn
                redirected -> SignInTurn.Redirected
                else -> null
            }
        }.stateIn(viewModelScope, WhileShown, null)

    fun taken(turn: SignInTurn) {
        when (turn) {
            SignInTurn.Redirected -> redirected.value = false
            SignInTurn.SignedIn -> signIn.acknowledge(SignInStep.SignedIn)
        }
    }

    // A redirect arrived when the step became "completing", or became a failure without being seen completing.
    private fun arrived(step: SignInStep): SignInStep {
        val previous = last
        last = step
        when {
            step == SignInStep.None -> redirected.value = false
            !signIn.canStart || step == previous -> Unit
            step == SignInStep.Completing || step is SignInStep.Failed && previous != SignInStep.Completing -> redirected.value = true
        }
        return step
    }
}
