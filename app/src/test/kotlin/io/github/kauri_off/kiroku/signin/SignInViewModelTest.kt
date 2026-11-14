package io.github.kauri_off.kiroku.signin

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.SignInFailure
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val failed = SignInStep.Failed(SignInFailure.Rejected)

    private class FakeSession : SessionRepository {
        val session = MutableStateFlow<Session?>(null)

        override fun observeSession(): Flow<Session> = session.filterNotNull()

        override suspend fun signOut() {
            session.value = Session.SignedOut
        }
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    // The states the screen shows for each step of the sign-in, in this order: none, completing, failed, signed in.
    private fun TestScope.shown(
        signIn: FakeSignIn,
        account: Boolean,
    ): List<SignInState> {
        val model = SignInViewModel(signIn, FakeSession(), account)
        showing(model.state)
        return listOf(SignInStep.None, SignInStep.Completing, failed, SignInStep.SignedIn).map {
            signIn.step.value = it
            model.state.value
        }
    }

    // What leaving the screen does to its view model: the store that holds it is cleared.
    private fun left(model: SignInViewModel) {
        val store = ViewModelStore()
        ViewModelProvider.create(store, viewModelFactory { initializer { model } })[SignInViewModel::class]
        store.clear()
    }

    @Test
    fun `the screen shows each step of a sign-in this data can start, and a success as signing in until the app leaves`() =
        test {
            val states = shown(FakeSignIn(), account = true)
            assertEquals(listOf(SignInState.Ready, SignInState.Completing, SignInState.Failed, SignInState.Completing), states)
        }

    @Test
    fun `a build without a client id and the demo each show their own form, whatever the step`() =
        test {
            assertEquals(List(4) { SignInState.NoClientId }, shown(FakeSignIn(canStart = false), account = true))
            assertEquals(List(4) { SignInState.NoAccount }, shown(FakeSignIn(canStart = false), account = false))
            assertEquals(List(4) { SignInState.NoAccount }, shown(FakeSignIn(canStart = true), account = false))
        }

    @Test
    fun `a session signed in while the screen shows is a finished sign-in`() =
        test {
            val session = FakeSession()
            val model = SignInViewModel(FakeSignIn(), session, account = true)
            showing(model.signedIn)
            session.session.value = Session.Expired(SignInId("a"))
            assertEquals(false, model.signedIn.value)
            session.session.value = Session.SignedIn(SignInId("b"))
            assertEquals(true, model.signedIn.value)
        }

    @Test
    fun `leaving the screen reads the failure it showed, and nothing else`() =
        test {
            for (step in listOf(SignInStep.None, SignInStep.Completing, SignInStep.SignedIn, failed)) {
                val signIn = FakeSignIn().apply { this.step.value = step }
                left(SignInViewModel(signIn, FakeSession(), account = true))
                assertEquals("$step", if (step == failed) listOf(failed) else emptyList(), signIn.acknowledged.toList())
            }
        }

    @Test
    fun `a redirect asks for the sign-in screen once, until the app says it showed it`() =
        test {
            val signIn = FakeSignIn()
            val handOver = SignInHandOver(signIn)
            val shown = showing(handOver.turn)
            assertNull(handOver.turn.value)
            signIn.redirected("x")
            assertEquals(SignInTurn.Redirected, handOver.turn.value)
            handOver.taken(SignInTurn.Redirected)
            assertNull(handOver.turn.value)
            signIn.step.value = failed
            assertNull(handOver.turn.value)

            shown.cancel()
            testScheduler.advanceTimeBy(60_000)
            showing(handOver.turn)
            assertNull(handOver.turn.value)
        }

    @Test
    fun `a failure seen without its redirect being seen asks for the sign-in screen too`() =
        test {
            val signIn = FakeSignIn().apply { step.value = failed }
            val handOver = SignInHandOver(signIn)
            showing(handOver.turn)
            assertEquals(SignInTurn.Redirected, handOver.turn.value)
            signIn.acknowledge(failed)
            assertNull(handOver.turn.value)
        }

    @Test
    fun `data that cannot sign in asks nothing of the app when a redirect arrives`() =
        test {
            val signIn = FakeSignIn(canStart = false)
            val handOver = SignInHandOver(signIn)
            showing(handOver.turn)
            signIn.redirected("x")
            signIn.step.value = failed
            assertNull(handOver.turn.value)
        }

    @Test
    fun `a success asks for the List over a redirect not acted on yet, and is acknowledged once the app went there`() =
        test {
            val signIn = FakeSignIn()
            val handOver = SignInHandOver(signIn)
            showing(handOver.turn)
            signIn.redirected("x")
            signIn.step.value = SignInStep.SignedIn
            assertEquals(SignInTurn.SignedIn, handOver.turn.value)
            handOver.taken(SignInTurn.SignedIn)
            assertEquals(listOf<SignInStep>(SignInStep.SignedIn), signIn.acknowledged.toList())
            assertNull(handOver.turn.value)
        }
}
