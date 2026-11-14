package io.github.kauri_off.kiroku.data.signin

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.SignInFailure
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.session.FailingCipher
import io.github.kauri_off.kiroku.data.session.TokenReply
import io.github.kauri_off.kiroku.data.session.granted
import io.github.kauri_off.kiroku.data.session.resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class MalSignInTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fixture by lazy { SignInFixture(folder.newFolder()) }
    private val grants get() = fixture.sessions.grants
    private val clock get() = fixture.sessions.clock

    @After
    fun close() = runBlocking { fixture.close() }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private fun failed(reason: SignInFailure) = SignInStep.Failed(reason)

    private fun mal(answer: TokenReply = granted("access-1", "refresh-1")) {
        fixture.sessions.answer = { answer }
    }

    private suspend fun session(): Session = fixture.session.observeSession().first()

    @Test
    fun aSignInOpensMalsPageWithTheBuildsRedirectAndAChallengeOfItsOwn() =
        test {
            val signIn = fixture.open()
            val first = fixture.started(signIn)
            val second = fixture.started(signIn)

            assertEquals(AUTHORIZE, first.substringBefore('?'))
            assertEquals(
                setOf("response_type", "client_id", "state", "redirect_uri", "code_challenge", "code_challenge_method"),
                first
                    .substringAfter('?')
                    .split('&')
                    .map { it.substringBefore('=') }
                    .toSet(),
            )
            assertEquals("code", first.asked("response_type"))
            assertEquals(CLIENT, first.asked("client_id"))
            assertEquals(REDIRECT, first.asked("redirect_uri"))
            assertEquals("plain", first.asked("code_challenge_method"))
            assertTrue(first.asked("code_challenge").matches(Regex("[A-Za-z0-9._~-]{43,128}")))
            assertTrue(first.asked("state").matches(Regex("[A-Za-z0-9._~-]{43,}")))
            assertNotEquals(first.asked("state"), first.asked("code_challenge"))
            assertNotEquals(first.asked("state"), second.asked("state"))
            assertNotEquals(first.asked("code_challenge"), second.asked("code_challenge"))
            assertEquals(SignInStep.None, signIn.step.value)
            assertTrue(signIn.canStart)
            assertEquals(emptyList<Map<String, String>>(), grants.toList())
        }

    @Test
    fun theRedirectOfTheSignInThatWaitsIsExchangedWithItsVerifierAndSignsIn() =
        test {
            mal()
            val signIn = fixture.open()
            val address = fixture.started(signIn)

            signIn.redirected(redirectOf(address))

            assertEquals(SignInStep.SignedIn, signIn.ended())
            assertTrue(session() is Session.SignedIn)
            val grant =
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to CODE,
                    "code_verifier" to address.asked("code_challenge"),
                    "redirect_uri" to REDIRECT,
                )
            assertEquals(listOf(grant), grants.toList())
            assertEquals(0, fixture.file.length())
            assertEquals(emptyList<SignInFailure>(), fixture.failures.toList())
            assertEquals(emptyList<String>(), fixture.sessions.reported)
        }

    // Runs [block] while nothing of the sign-in, whose work has this one thread, can be dealt with.
    private suspend fun CoroutineScope.held(
        one: CoroutineDispatcher,
        block: () -> Unit,
    ) {
        val busy = CountDownLatch(1)
        val held = CompletableDeferred<Unit>()
        val holder =
            launch(one) {
                held.complete(Unit)
                busy.await()
            }
        held.await()
        try {
            block()
        } finally {
            busy.countDown()
        }
        holder.join()
    }

    @Test
    fun aRedirectIsBeingCompletedFromTheMomentItArrives() =
        test {
            mal()
            val one = Dispatchers.IO.limitedParallelism(1)
            val signIn = fixture.open(dispatcher = one)
            val address = fixture.started(signIn)

            held(one) {
                signIn.redirected(redirectOf(address))
                assertEquals(SignInStep.Completing, signIn.step.value)
            }

            assertEquals(SignInStep.SignedIn, signIn.ended())
        }

    @Test
    fun aRedirectThatArrivesAfterASuccessNobodyAcknowledgedNeverShowsSigningIn() =
        test {
            mal()
            val one = Dispatchers.IO.limitedParallelism(1)
            val signIn = fixture.open(dispatcher = one)
            signIn.redirected(redirectOf(fixture.started(signIn)))
            assertEquals(SignInStep.SignedIn, signIn.ended())
            fixture.idle()

            held(one) {
                signIn.redirected("$REDIRECT?code=$CODE&state=any")
                assertEquals(SignInStep.SignedIn, signIn.step.value)
            }

            fixture.idle()
            assertEquals(SignInStep.SignedIn, signIn.step.value)
        }

    @Test
    fun aRedirectWithAnotherStateOrNoneFailsAndTheSignInGoesOnWaiting() =
        test {
            mal()
            val signIn = fixture.open()
            val address = fixture.started(signIn)
            val state = address.asked("state")
            val wrong =
                listOf(
                    "$REDIRECT?code=$CODE&state=${state.dropLast(1)}",
                    "$REDIRECT?code=$CODE&state=${state}x",
                    "$REDIRECT?code=$CODE&state=",
                    "$REDIRECT?code=$CODE",
                    "$REDIRECT?code=$CODE&state=$state&state=$state",
                    "$REDIRECT?code=$CODE&state=${address.asked("code_challenge")}",
                )
            for (redirect in wrong) {
                signIn.redirected(redirect)
                assertEquals(redirect, failed(SignInFailure.WrongState), signIn.ended())
            }
            assertEquals(emptyList<Map<String, String>>(), grants.toList())
            assertEquals(Session.SignedOut, session())

            signIn.redirected(redirectOf(address))
            assertEquals(SignInStep.SignedIn, signIn.ended())
        }

    @Test
    fun aRedirectNobodyAskedForFails() =
        test {
            mal()
            val signIn = fixture.open()

            signIn.redirected("$REDIRECT?code=$CODE&state=any")

            assertEquals(failed(SignInFailure.Unasked), signIn.ended())
            assertEquals(emptyList<Map<String, String>>(), grants.toList())
            assertEquals(Session.SignedOut, session())
            assertEquals(listOf(SignInFailure.Unasked), fixture.failures.toList())
        }

    @Test
    fun aRedirectWithoutACodeFailsAndEndsItsSignIn() =
        test {
            mal()
            val signIn = fixture.open()
            val missing =
                listOf<(String) -> String>(
                    { "$REDIRECT?state=$it" },
                    { "$REDIRECT?code=&state=$it" },
                    { "$REDIRECT?error=access_denied&state=$it" },
                    { "$REDIRECT?code=$CODE&error=access_denied&state=$it" },
                    { "$REDIRECT?code=$CODE&code=$CODE&state=$it" },
                )
            for (redirect in missing) {
                val address = fixture.started(signIn)
                signIn.redirected(redirect(address.asked("state")))
                assertEquals(failed(SignInFailure.Denied), signIn.ended())
                signIn.redirected(redirectOf(address))
                assertEquals(failed(SignInFailure.Unasked), signIn.ended())
            }
            assertEquals(emptyList<Map<String, String>>(), grants.toList())
            assertEquals(Session.SignedOut, session())
        }

    @Test
    fun aRedirectForASignInStartedTooLongAgoFails() =
        test {
            mal()
            val signIn = fixture.open()
            val old = fixture.started(signIn)
            clock.advance(STARTED_WITHIN.plusSeconds(1))
            signIn.redirected(redirectOf(old))
            assertEquals(failed(SignInFailure.Old), signIn.ended())
            clock.advance(STARTED_WITHIN.plusSeconds(1).negated())
            signIn.redirected(redirectOf(old))
            assertEquals(failed(SignInFailure.Unasked), signIn.ended())

            val early = fixture.started(signIn)
            clock.advance(STARTED_WITHIN.plusSeconds(1).negated())
            signIn.redirected(redirectOf(early))
            assertEquals(failed(SignInFailure.Old), signIn.ended())
            assertEquals(emptyList<Map<String, String>>(), grants.toList())
            assertEquals(Session.SignedOut, session())

            val inTime = fixture.started(signIn)
            clock.advance(STARTED_WITHIN)
            signIn.redirected(redirectOf(inTime))
            assertEquals(SignInStep.SignedIn, signIn.ended())
        }

    @Test
    fun aClockSetBackALittleAfterTheStartDoesNotEndTheSignIn() =
        test {
            mal()
            val signIn = fixture.open()
            val address = fixture.started(signIn)
            clock.advance(Duration.ofSeconds(-2))

            signIn.redirected("$REDIRECT?code=$CODE&state=another")
            assertEquals(failed(SignInFailure.WrongState), signIn.ended())
            signIn.redirected(redirectOf(address))

            assertEquals(SignInStep.SignedIn, signIn.ended())
        }

    @Test
    fun aRedirectWithoutTheStateNeverEndsTheSignInThatWaits() =
        test {
            mal()
            val signIn = fixture.open()
            val address = fixture.started(signIn)
            val late = STARTED_WITHIN.plusSeconds(1)
            for (step in listOf(late, late.multipliedBy(-2))) {
                clock.advance(step)
                for (other in listOf("$REDIRECT?code=$CODE&state=another", "$REDIRECT?code=$CODE", "$REDIRECT?error=access_denied")) {
                    signIn.redirected(other)
                    assertEquals(other, failed(SignInFailure.WrongState), signIn.ended())
                }
            }
            assertEquals(emptyList<Map<String, String>>(), grants.toList())

            clock.advance(late)
            signIn.redirected(redirectOf(address))
            assertEquals(SignInStep.SignedIn, signIn.ended())
        }

    @Test
    fun aNewSignInForgetsASuccessThatWasNotAcknowledged() =
        test {
            mal()
            val signIn = fixture.open()
            signIn.redirected(redirectOf(fixture.started(signIn)))
            assertEquals(SignInStep.SignedIn, signIn.ended())

            val address = fixture.started(signIn)
            assertEquals(SignInStep.None, signIn.step.value)
            signIn.redirected("$REDIRECT?error=access_denied&state=${address.asked("state")}")

            assertEquals(failed(SignInFailure.Denied), signIn.ended())
            assertEquals(1, grants.size)
        }

    @Test
    fun aRepeatedRedirectFailsAndChangesNoSession() =
        test {
            mal()
            val signIn = fixture.open()
            val redirect = redirectOf(fixture.started(signIn))
            signIn.redirected(redirect)
            assertEquals(SignInStep.SignedIn, signIn.ended())
            signIn.acknowledge(SignInStep.SignedIn)
            val signedIn = session()

            signIn.redirected(redirect)

            assertEquals(failed(SignInFailure.Unasked), signIn.ended())
            assertEquals(1, grants.size)
            assertEquals(signedIn, session())
        }

    @Test
    fun aRedirectRepeatedWhileItsSignInIsBeingCompletedIsNotAFailure() =
        test {
            val answer = CompletableDeferred<TokenReply>()
            fixture.sessions.answer = { answer.await() }
            val signIn = fixture.open()
            val redirect = redirectOf(fixture.started(signIn))

            signIn.redirected(redirect)
            signIn.redirected(redirect)
            answer.complete(granted("access-1", "refresh-1"))

            assertEquals(SignInStep.SignedIn, signIn.ended())
            signIn.acknowledge(SignInStep.SignedIn)
            fixture.idle()
            assertEquals(SignInStep.None, signIn.step.value)
            assertEquals(emptyList<SignInFailure>(), fixture.failures.toList())
            assertEquals(1, grants.size)
        }

    @Test
    fun aRedirectRepeatedWhileItsExchangeFailsLeavesThatFailureAndNoSigningIn() =
        test {
            lateinit var signIn: MalSignIn
            lateinit var redirect: String
            fixture.sessions.answer = {
                signIn.redirected(redirect)
                TokenReply.Answer(400, "no")
            }
            signIn = fixture.open()
            redirect = redirectOf(fixture.started(signIn))

            signIn.redirected(redirect)
            fixture.idle()

            assertEquals(failed(SignInFailure.Rejected), signIn.step.value)
            assertEquals(listOf(SignInFailure.Rejected), fixture.failures.toList())
            assertEquals(1, grants.size)
        }

    @Test
    fun whateverRedirectsArriveWhenTheSignInLeavesBeingCompleted() =
        test {
            val random = Random(20261114)
            val signIn = fixture.open()
            repeat(100) { round ->
                val granting = random.nextBoolean()
                val after = random.nextLong(3)
                fixture.sessions.answer = {
                    delay(after)
                    if (granting) granted("access-$round", "refresh-$round") else TokenReply.Answer(400, "no")
                }
                val redirect = redirectOf(fixture.started(signIn))
                val arriving =
                    listOf(
                        redirect,
                        redirect,
                        "$REDIRECT?code=$CODE&state=another",
                        "",
                        redirect,
                    ).map { it to random.nextLong(4) }
                coroutineScope {
                    for ((uri, wait) in arriving) {
                        launch(Dispatchers.Default) {
                            delay(wait)
                            signIn.redirected(uri)
                        }
                    }
                }
                fixture.idle()

                val end = signIn.step.value
                if (granting) {
                    assertEquals(
                        "round $round",
                        SignInStep.SignedIn,
                        end,
                    )
                } else {
                    assertTrue("round $round: $end", end is SignInStep.Failed)
                }
                assertEquals(granting, session() is Session.SignedIn)
                assertEquals(round + 1, grants.size)
                signIn.acknowledge(end)
                assertEquals(SignInStep.None, signIn.step.value)
                fixture.session.signOut()
            }
        }

    @Test
    fun aTokenRequestThatGetsNoAnswerIsGivenUpAndTheSignInFails() =
        test {
            fixture.sessions.answer = { awaitCancellation() }
            val signIn = fixture.open(exchangeWithin = Duration.ofMillis(200))
            val redirect = redirectOf(fixture.started(signIn))

            signIn.redirected(redirect)
            signIn.redirected(redirect)

            assertEquals(failed(SignInFailure.NoTokens), signIn.ended())
            assertEquals(Session.SignedOut, session())
            assertEquals(emptyList<String>(), fixture.sessions.reported)
            mal()
            signIn.redirected(redirectOf(fixture.started(signIn)))
            assertEquals(SignInStep.SignedIn, signIn.ended())
        }

    @Test
    fun aRedirectThatArrivesWhenTheFlowsWorkHasEndedLeavesNoSigningIn() =
        test {
            val signIn = fixture.open()
            fixture.close()

            signIn.redirected("$REDIRECT?code=$CODE&state=any")

            assertEquals(SignInStep.None, signIn.ended())
        }

    @Test
    fun aRedirectThatFailsDoesNotHideASignInThatWasNotAcknowledged() =
        test {
            mal()
            val signIn = fixture.open()
            signIn.redirected(redirectOf(fixture.started(signIn)))
            assertEquals(SignInStep.SignedIn, signIn.ended())

            signIn.redirected("$REDIRECT?code=$CODE&state=any")
            fixture.idle()

            assertEquals(listOf(SignInFailure.Unasked), fixture.failures.toList())
            assertEquals(SignInStep.SignedIn, signIn.step.value)
        }

    @Test
    fun anAddressThatIsNotTheBuildsRedirectFailsAndTheSignInGoesOnWaiting() =
        test {
            mal()
            val signIn = fixture.open()
            val address = fixture.started(signIn)
            val query = redirectOf(address).substringAfter('?')
            for (other in listOf("", "io.github.kauri-off.kiroku://oauth?$query", "$REDIRECT.x?$query", "$REDIRECT/x?$query")) {
                signIn.redirected(other)
                assertEquals(other, failed(SignInFailure.Unreadable), signIn.ended())
            }
            assertEquals(emptyList<Map<String, String>>(), grants.toList())

            signIn.redirected(redirectOf(address))
            assertEquals(SignInStep.SignedIn, signIn.ended())
        }

    @Test
    fun aNewSignInReplacesTheOneThatWaited() =
        test {
            mal()
            val signIn = fixture.open()
            val first = fixture.started(signIn)
            val second = fixture.started(signIn)

            signIn.redirected(redirectOf(first))
            assertEquals(failed(SignInFailure.WrongState), signIn.ended())
            signIn.redirected(redirectOf(second))
            assertEquals(SignInStep.SignedIn, signIn.ended())
            assertEquals(second.asked("code_challenge"), grants.single()["code_verifier"])
        }

    @Test
    fun anAnswerThatIsNotASuccessOrLacksATokenSignsNobodyIn() =
        test {
            val printed = resource("mal/authorization/token.json")
            val answers =
                listOf(
                    TokenReply.Answer(400, printed) to SignInFailure.Rejected,
                    TokenReply.Answer(401, printed) to SignInFailure.Rejected,
                    TokenReply.Answer(403, printed) to SignInFailure.NoTokens,
                    TokenReply.Answer(500, printed) to SignInFailure.NoTokens,
                    TokenReply.Answer(302, printed) to SignInFailure.NoTokens,
                    TokenReply.NoAnswer(DataError.NoConnection) to SignInFailure.NoTokens,
                    TokenReply.NoAnswer(DataError.Refused) to SignInFailure.NoTokens,
                    TokenReply.Answer(200, printed.replace("ACCESS_TOKEN", "")) to SignInFailure.NoTokens,
                    TokenReply.Answer(200, printed.replace("\"REFRESH_TOKEN\"", "null")) to SignInFailure.NoTokens,
                    TokenReply.Answer(200, printed.replace("refresh_token", "refresh")) to SignInFailure.NoTokens,
                    TokenReply.Answer(200, "") to SignInFailure.NoTokens,
                )
            val signIn = fixture.open()
            for ((answer, reason) in answers) {
                mal(answer)
                signIn.redirected(redirectOf(fixture.started(signIn)))
                assertEquals(failed(reason), signIn.ended())
                assertEquals(Session.SignedOut, session())
            }
            assertEquals(answers.size, grants.size)
            assertEquals(Session.SignedOut, fixture.reopen().let { session() })
        }

    @Test
    fun anExceptionOfTheTokenEndpointSignsNobodyInAndIsReportedWithoutItsMessage() =
        test {
            fixture.sessions.answer = { grant -> throw IllegalStateException("could not post $grant") }
            val signIn = fixture.open()

            signIn.redirected(redirectOf(fixture.started(signIn)))

            assertEquals(failed(SignInFailure.NoTokens), signIn.ended())
            assertEquals(Session.SignedOut, session())
            assertEquals(listOf(IllegalStateException::class.java.name), fixture.sessions.reported)
            mal()
            signIn.redirected(redirectOf(fixture.started(signIn)))
            assertEquals(SignInStep.SignedIn, signIn.ended())
        }

    @Test
    fun tokensThatCannotBeStoredSignNobodyIn() =
        test {
            mal()
            val signIn = fixture.open(FailingCipher(fixture.sessions.cipher, seals = false))

            signIn.redirected(redirectOf(fixture.started(signIn)))

            assertEquals(failed(SignInFailure.NotStored), signIn.ended())
            assertEquals(Session.SignedOut, session())
            assertEquals(Session.SignedOut, fixture.reopen().let { session() })
        }

    @Test
    fun newInstancesOverTheSameFilesCompleteASignInFromItsRedirectAndTakeItOnce() =
        test {
            mal()
            val address = fixture.started(fixture.open())
            val redirect = redirectOf(address)

            val next = fixture.reopen()
            assertEquals(SignInStep.None, next.step.value)
            next.redirected(redirect)

            assertEquals(SignInStep.SignedIn, next.ended())
            val signedIn = session()
            assertTrue(signedIn is Session.SignedIn)
            assertEquals(address.asked("code_challenge"), grants.single()["code_verifier"])

            val third = fixture.reopen()
            assertEquals(signedIn, session())
            third.redirected(redirect)
            assertEquals(failed(SignInFailure.Unasked), third.ended())
            assertEquals(1, grants.size)
            assertEquals(signedIn, session())
        }

    @Test
    fun aPageThatNothingOpensIsAFailedSignIn() =
        test {
            fixture.browser.opens = false
            val signIn = fixture.open()

            signIn.start(fixture.browser)

            assertEquals(1, fixture.browser.opened.size)
            assertEquals(failed(SignInFailure.NoBrowser), signIn.step.value)
        }

    @Test
    fun aBuildWithoutAClientIdOpensNoPage() =
        test {
            fixture.clientId = " "
            val signIn = fixture.open()
            assertFalse(signIn.canStart)

            signIn.start(fixture.browser)

            assertEquals(emptyList<String>(), fixture.browser.opened.toList())
            assertEquals(failed(SignInFailure.Unavailable), signIn.step.value)
            assertFalse(fixture.file.exists())
        }

    @Test
    fun aSignInThatCannotBeStoredOpensNoPage() =
        test {
            assertTrue(fixture.file.mkdir())
            assertTrue(File(fixture.file, "kept").createNewFile())
            val signIn = fixture.open()

            signIn.start(fixture.browser)

            assertEquals(emptyList<String>(), fixture.browser.opened.toList())
            assertEquals(failed(SignInFailure.NotStored), signIn.step.value)
        }

    @Test
    fun aFailureStaysUntilItIsAcknowledgedOrASignInStarts() =
        test {
            val signIn = fixture.open()
            signIn.redirected("$REDIRECT?code=$CODE&state=any")
            val failure = signIn.ended()
            assertEquals(failed(SignInFailure.Unasked), failure)

            signIn.acknowledge(SignInStep.SignedIn)
            signIn.acknowledge(failed(SignInFailure.Old))
            assertEquals(failure, signIn.step.value)
            signIn.acknowledge(failure)
            assertEquals(SignInStep.None, signIn.step.value)

            signIn.redirected("$REDIRECT?code=$CODE&state=any")
            assertEquals(failure, signIn.ended())
            signIn.start(fixture.browser)
            assertEquals(SignInStep.None, signIn.step.value)
        }
}
