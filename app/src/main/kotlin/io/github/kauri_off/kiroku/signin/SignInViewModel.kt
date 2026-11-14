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

    // The sign-in being completed failed: shown if its screen is on top, else read, as the person left it.
    data class Failed(
        val step: SignInStep.Failed,
    ) : SignInTurn
}

// The app's side of the sign-in, for as long as the app is shown: what it must act on, until it acknowledges it.
class SignInHandOver(
    private val signIn: SignIn,
) : ViewModel() {
    private val redirected = MutableStateFlow(false)
    private val failed = MutableStateFlow<SignInStep.Failed?>(null)

    // The step the last turn was decided from; it outlives the screen's collection, so a step is acted on once.
    private var last: SignInStep = SignInStep.None

    val turn: StateFlow<SignInTurn?> =
        combine(signIn.step.map(::arrived), redirected, failed) { step, redirected, failed ->
            when {
                step == SignInStep.SignedIn -> SignInTurn.SignedIn
                redirected -> SignInTurn.Redirected
                failed != null -> SignInTurn.Failed(failed)
                else -> null
            }
        }.stateIn(viewModelScope, WhileShown, null)

    // [onScreen]: the sign-in screen is on top once the app has acted on [turn].
    fun taken(
        turn: SignInTurn,
        onScreen: Boolean,
    ) {
        when (turn) {
            SignInTurn.Redirected -> redirected.value = false
            SignInTurn.SignedIn -> signIn.acknowledge(SignInStep.SignedIn)
            is SignInTurn.Failed -> {
                failed.value = null
                if (!onScreen) signIn.acknowledge(turn.step)
            }
        }
    }

    // A redirect arrived when the step became "completing", or became a failure without being seen completing.
    private fun arrived(step: SignInStep): SignInStep {
        val previous = last
        last = step
        when {
            step == SignInStep.None -> {
                redirected.value = false
                failed.value = null
            }
            !signIn.canStart || step == previous -> Unit
            step == SignInStep.Completing || step is SignInStep.Failed && previous != SignInStep.Completing -> redirected.value = true
            step is SignInStep.Failed && !redirected.value -> failed.value = step
        }
        return step
    }
}
