package io.github.kauri_off.kiroku.core.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NoSignInTest {
    private val reported = mutableListOf<SignInFailure>()
    private val signIn = NoSignIn(reported::add)
    private val unavailable = SignInStep.Failed(SignInFailure.Unavailable)

    @Test
    fun `a source without an account opens no page and fails the sign-in`() =
        runTest {
            var opened = false
            signIn.start {
                opened = true
                true
            }
            assertFalse(opened)
            assertFalse(signIn.canStart)
            assertEquals(unavailable, signIn.step.value)
            assertEquals(listOf(SignInFailure.Unavailable), reported)
        }

    @Test
    fun `a source without an account fails every redirect until the failure is acknowledged`() {
        signIn.redirected("io.github.kauri-off.kiroku://oauth?code=c&state=s")
        assertEquals(unavailable, signIn.step.value)
        signIn.acknowledge(SignInStep.SignedIn)
        assertEquals(unavailable, signIn.step.value)
        signIn.acknowledge(unavailable)
        assertEquals(SignInStep.None, signIn.step.value)
    }
}
