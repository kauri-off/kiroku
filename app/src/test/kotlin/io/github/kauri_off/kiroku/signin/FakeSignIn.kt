package io.github.kauri_off.kiroku.signin

import io.github.kauri_off.kiroku.core.repository.SignIn
import io.github.kauri_off.kiroku.core.repository.SignInBrowser
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList

// A sign-in whose step the test sets; a start can be held until its caller is cancelled.
class FakeSignIn(
    override val canStart: Boolean = true,
) : SignIn {
    override val step = MutableStateFlow<SignInStep>(SignInStep.None)
    val starts = CopyOnWriteArrayList<SignInBrowser>()
    val acknowledged = CopyOnWriteArrayList<SignInStep>()
    var holdsStart = false
    var cancelledStarts = 0
        private set

    override suspend fun start(browser: SignInBrowser) {
        starts += browser
        step.value = SignInStep.None
        if (holdsStart) {
            try {
                awaitCancellation()
            } finally {
                cancelledStarts++
            }
        }
    }

    override fun redirected(uri: String) {
        step.value = SignInStep.Completing
    }

    override fun acknowledge(step: SignInStep) {
        acknowledged += step
        this.step.compareAndSet(step, SignInStep.None)
    }
}

// A graph over a MAL account whose sign-in is [signIn].
class FakeSignInGraph(
    graph: AppGraph,
    override val signIn: FakeSignIn = FakeSignIn(),
) : AppGraph by graph,
    SignInOwner
