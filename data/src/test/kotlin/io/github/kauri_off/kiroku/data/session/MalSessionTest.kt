package io.github.kauri_off.kiroku.data.session

import app.cash.turbine.test
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.security.ProviderException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor

@RunWith(RobolectricTestRunner::class)
class MalSessionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fixture by lazy { SessionFixture(folder.newFolder()) }
    private val expired = Outcome.Failure(DataError.SessionExpired)

    @After
    fun close() = runBlocking { fixture.close() }

    private fun test(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private suspend fun signedIn(
        access: String = "access-1",
        refresh: String = "refresh-1",
    ): MalSession = fixture.open().also { assertEquals(Outcome.Success(Unit), it.signIn(TokenGrant(access, refresh))) }

    private suspend fun MalSession.now(): Session = observeSession().first()

    private suspend fun stored(): String =
        fixture
            .apply { close() }
            .store()
            .read()
            .text()

    @Test
    fun theSessionHasNoValueBeforeTheStoreWasRead() =
        test {
            val id = signedIn().now()
            val reading = CountDownLatch(1)
            val read = CountDownLatch(1)
            val real = AesGcmCipher { fixture.key }
            val slow =
                object : TokenCipher by real {
                    override fun open(sealed: ByteArray): ByteArray {
                        reading.countDown()
                        read.await()
                        return real.open(sealed)
                    }
                }
            try {
                fixture.reopen(slow).observeSession().test {
                    assertTrue(reading.await(10, TimeUnit.SECONDS))
                    expectNoEvents()
                    read.countDown()
                    assertEquals(id, awaitItem())
                }
            } finally {
                read.countDown()
            }
        }

    @Test
    fun aSignInIsANewSessionThatTheNextProcessAndARefreshKeep() =
        test {
            val session = fixture.open()
            assertEquals(Session.SignedOut, session.now())
            session.signIn(TokenGrant("access-1", "refresh-1"))
            val first = session.now()
            assertTrue(first is Session.SignedIn)
            assertEquals(first, fixture.reopen().now())

            val again = fixture.reopen()
            fixture.answer = { granted("access-2", "refresh-2") }
            assertEquals(Outcome.Success("access-2"), again.authorised(FakeMal("access-2").request))
            assertEquals(first, again.now())
            assertEquals(first, fixture.reopen().now())

            val other = fixture.reopen()
            other.signIn(TokenGrant("access-3", "refresh-3"))
            assertTrue(other.now() is Session.SignedIn)
            assertNotEquals(first, other.now())
        }

    @Test
    fun aSignInThatCannotBeStoredSignsNobodyIn() =
        test {
            val unsealing =
                object : TokenCipher {
                    override fun seal(plain: ByteArray): ByteArray = throw ProviderException("no Keystore")

                    override fun open(sealed: ByteArray): ByteArray = throw ProviderException("no Keystore")
                }
            val session = fixture.open(unsealing)
            assertEquals(Outcome.Failure(DataError.StorageError), session.signIn(TokenGrant("access-1", "refresh-1")))
            assertEquals(Session.SignedOut, session.now())
            val mal = FakeMal("access-1")
            assertEquals(expired, session.authorised(mal.request))
            assertEquals(emptyList<String>(), mal.sent)
            assertEquals(Session.SignedOut, fixture.reopen().now())
        }

    @Test
    fun anAcceptedTokenIsSentOnceAndNothingIsRefreshed() =
        test {
            val session = signedIn()
            val mal = FakeMal("access-1")
            assertEquals(Outcome.Success("access-1"), session.authorised(mal.request))
            assertEquals(listOf("access-1"), mal.sent)
            val failed = Outcome.Failure(DataError.Refused)
            assertEquals(failed, session.authorised<Unit> { Attempt.Answered(failed) })
            assertEquals(emptyList<Map<String, String>>(), fixture.grants)
        }

    @Test
    fun aRejectedTokenIsRefreshedOnceAndTheRequestRepeatedOnce() =
        test {
            val session = signedIn()
            val mal = FakeMal("access-2")
            fixture.answer = { granted("access-2", "refresh-2") }
            assertEquals(Outcome.Success("access-2"), session.authorised(mal.request))
            assertEquals(listOf("access-1", "access-2"), mal.sent)
            assertEquals(listOf(refreshGrant("refresh-1")), fixture.grants)

            assertEquals(Outcome.Success("access-2"), session.authorised(mal.request))
            assertEquals(1, fixture.grants.size)
            assertTrue(stored().endsWith(" access-2 refresh-2 ${fixture.clock.now.epochSecond}"))
        }

    @Test
    fun aTokenRejectedAgainAfterItsRefreshIsTheOutcomeSessionExpiredAndNoLoop() =
        test {
            val session = signedIn()
            val id = session.now()
            val mal = FakeMal()
            var refreshes = 0
            fixture.answer = {
                refreshes++
                granted("access-${refreshes + 1}", "refresh-${refreshes + 1}")
            }
            assertEquals(expired, session.authorised(mal.request))
            assertEquals(listOf("access-1", "access-2"), mal.sent)
            assertEquals(1, refreshes)
            assertEquals(id, session.now())
        }

    // All ten are sent before any is answered. They run one at a time, so each is parked in the refresh before the
    // next goes on, and the token endpoint answers only after the last of them.
    private suspend fun tenRejectedAtOnce(
        session: MalSession,
        mal: FakeMal,
        reply: TokenReply,
    ): List<Outcome<String>> =
        withContext(Dispatchers.IO.limitedParallelism(1)) {
            val oneAtATime = coroutineContext[ContinuationInterceptor]!!
            val sent = AtomicInteger()
            val allSent = CompletableDeferred<Unit>()
            val rejected = CountDownLatch(10)
            fixture.answer = {
                withContext(Dispatchers.IO) { assertTrue(rejected.await(10, TimeUnit.SECONDS)) }
                withContext(oneAtATime) {}
                reply
            }
            val request =
                AuthorisedRequest { token ->
                    if (token == "access-1") {
                        if (sent.incrementAndGet() == 10) allSent.complete(Unit)
                        allSent.await()
                    }
                    mal.request.send(token).also { if (token == "access-1" && it is Attempt.Rejected) rejected.countDown() }
                }
            (1..10).map { async { session.authorised(request) } }.awaitAll()
        }

    @Test
    fun tenRequestsRejectedAtOnceCauseOneRefreshAndOneRetryEach() =
        test {
            val session = signedIn()
            val mal = FakeMal("access-2")
            val outcomes = tenRejectedAtOnce(session, mal, granted("access-2", "refresh-2"))
            assertEquals(List(10) { Outcome.Success("access-2") }, outcomes)
            assertEquals(listOf(refreshGrant("refresh-1")), fixture.grants)
            assertEquals(List(10) { "access-1" } + List(10) { "access-2" }, mal.sent.sorted())
        }

    @Test
    fun tenRequestsRejectedAtOnceShareARefreshThatFailed() =
        test {
            val session = signedIn()
            val id = session.now()
            val mal = FakeMal()
            val outcomes = tenRejectedAtOnce(session, mal, TokenReply.NoAnswer(DataError.NoConnection))
            assertEquals(List(10) { Outcome.Failure(DataError.NoConnection) }, outcomes)
            assertEquals(1, fixture.grants.size)
            assertEquals(List(10) { "access-1" }, mal.sent)
            assertEquals(id, session.now())
        }

    @Test
    fun tenRequestsRejectedAtOnceShareARefreshThatMalRejected() =
        test {
            val session = signedIn()
            val mal = FakeMal()
            val outcomes = tenRejectedAtOnce(session, mal, TokenReply.Answer(401, ""))
            assertEquals(List(10) { expired }, outcomes)
            assertEquals(1, fixture.grants.size)
            assertEquals(List(10) { "access-1" }, mal.sent)
            assertEquals(Session.Expired, session.now())
        }

    @Test
    fun aRejectedRefreshEndsInExpiredWithTheTokensGone() =
        test {
            val session = signedIn()
            val mal = FakeMal()
            fixture.answer = { TokenReply.Answer(400, "") }
            session.observeSession().test {
                assertTrue(awaitItem() is Session.SignedIn)
                assertEquals(expired, session.authorised(mal.request))
                assertEquals(Session.Expired, awaitItem())
            }
            assertEquals(listOf("access-1"), mal.sent)

            // Nothing is sent or refreshed any more, in this process or the next.
            assertEquals(expired, session.authorised(mal.request))
            session.renewIfDue()
            assertEquals(1L, fixture.file.length())
            val next = fixture.reopen()
            assertEquals(Session.Expired, next.now())
            assertEquals(expired, next.authorised(mal.request))
            assertEquals(listOf("access-1"), mal.sent)
            assertEquals(1, fixture.grants.size)
        }

    @Test
    fun aRefreshThatFailedForNowKeepsTheTokensAndTheSession() =
        test {
            val printed = resource("mal/authorization/token.json")
            val temporary =
                listOf(
                    TokenReply.NoAnswer(DataError.NoConnection) to DataError.NoConnection,
                    TokenReply.Answer(500, "") to DataError.ServerError,
                    TokenReply.Answer(403, "") to DataError.ServerError,
                    TokenReply.Answer(200, printed.replace("REFRESH_TOKEN", "")) to DataError.ServerError,
                    TokenReply.Answer(200, printed.replace("access_token", "token")) to DataError.ServerError,
                    TokenReply.Answer(200, "") to DataError.ServerError,
                )
            val session = signedIn()
            val id = session.now()
            val before = fixture.file.readBytes().toList()
            for ((reply, error) in temporary) {
                val mal = FakeMal()
                fixture.answer = { reply }
                assertEquals(Outcome.Failure(error), session.authorised(mal.request))
                assertEquals(listOf("access-1"), mal.sent)
                assertEquals(id, session.now())
                assertEquals(before, fixture.file.readBytes().toList())
            }
            assertEquals(List(temporary.size) { refreshGrant("refresh-1") }, fixture.grants)

            val next = fixture.reopen()
            assertEquals(id, next.now())
            assertEquals(Outcome.Success("access-1"), next.authorised(FakeMal("access-1").request))
        }

    @Test
    fun anExceptionOfTheTokenEndpointIsReportedAndKeepsTheSession() =
        test {
            val session = signedIn()
            val id = session.now()
            val thrown = IllegalStateException("broken")
            fixture.answer = { throw thrown }
            assertEquals(Outcome.Failure(DataError.ServerError), session.authorised(FakeMal().request))
            assertEquals(listOf<Throwable>(thrown), fixture.unexpected)
            assertEquals(id, session.now())
        }

    @Test
    fun signedOutAndBeforeAnySignInNothingIsSent() =
        test {
            val mal = FakeMal("access-1")
            val session = fixture.open()
            assertEquals(expired, session.authorised(mal.request))
            session.signIn(TokenGrant("access-1", "refresh-1"))
            session.observeSession().test {
                assertTrue(awaitItem() is Session.SignedIn)
                session.signOut()
                assertEquals(Session.SignedOut, awaitItem())
            }
            assertEquals(expired, session.authorised(mal.request))
            session.renewIfDue()
            assertEquals(emptyList<String>(), mal.sent)
            assertEquals(0L, fixture.file.length())
            assertEquals(Session.SignedOut, fixture.reopen().now())
            assertEquals(emptyList<Map<String, String>>(), fixture.grants)
        }

    @Test
    fun aRequestIsNotRepeatedWithTheTokenOfAnotherSignIn() =
        test {
            val session = signedIn()
            val sending = CompletableDeferred<Unit>()
            val answered = CompletableDeferred<Unit>()
            val sent = mutableListOf<String>()
            val outcome =
                async {
                    session.authorised<Unit> { token ->
                        sent += token
                        sending.complete(Unit)
                        answered.await()
                        Attempt.Rejected
                    }
                }
            sending.await()
            session.signOut()
            session.signIn(TokenGrant("other-access", "other-refresh"))
            answered.complete(Unit)
            assertEquals(expired, outcome.await())
            assertEquals(listOf("access-1"), sent)
            assertEquals(emptyList<Map<String, String>>(), fixture.grants)
        }

    @Test
    fun aRefreshIsFinishedAndStoredThoughItsRequestWasCancelled() =
        test {
            val session = signedIn()
            val asked = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<TokenReply>()
            fixture.answer = {
                asked.complete(Unit)
                answer.await()
            }
            val request = launch { session.authorised(FakeMal().request) }
            asked.await()
            request.cancelAndJoin()
            answer.complete(granted("access-2", "refresh-2"))

            val mal = FakeMal("access-2")
            assertEquals(Outcome.Success("access-2"), session.authorised(mal.request))
            assertEquals(listOf("access-2"), mal.sent)
            assertEquals(1, fixture.grants.size)
            assertTrue(stored().contains(" access-2 refresh-2 "))
        }

    @Test
    fun aRequestWaitsForARunningRefreshInsteadOfSendingTheOldToken() =
        test {
            val session = signedIn()
            val asked = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<TokenReply>()
            fixture.answer = {
                asked.complete(Unit)
                answer.await()
            }
            val mal = FakeMal("access-2")
            val first = async { session.authorised(mal.request) }
            asked.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { session.authorised(mal.request) }
            assertEquals(listOf("access-1"), mal.sent)
            answer.complete(granted("access-2", "refresh-2"))
            assertEquals(List(2) { Outcome.Success("access-2") }, listOf(first, second).awaitAll())
            assertEquals(listOf("access-1", "access-2", "access-2"), mal.sent)
        }

    @Test
    fun tokensOlderThanTheSetAgeAreRenewedWhenTheSessionIsFirstRead() =
        test {
            val id = signedIn().now()
            fixture.clock.advance(RENEW_AFTER - Duration.ofSeconds(1))
            val young = fixture.reopen()
            assertEquals(id, young.now())
            young.renewIfDue()
            assertEquals(Outcome.Success("access-1"), young.authorised(FakeMal("access-1").request))
            assertEquals(emptyList<Map<String, String>>(), fixture.grants)

            // Reading the session is enough: no request has to be rejected first.
            fixture.clock.advance(Duration.ofSeconds(1))
            val asked = CompletableDeferred<Unit>()
            fixture.answer = {
                asked.complete(Unit)
                granted("access-2", "refresh-2")
            }
            val old = fixture.reopen()
            assertEquals(id, old.now())
            withTimeout(5_000) { asked.await() }
            val mal = FakeMal("access-2")
            assertEquals(Outcome.Success("access-2"), old.authorised(mal.request))
            assertEquals(listOf("access-2"), mal.sent)
            assertEquals(listOf(refreshGrant("refresh-1")), fixture.grants)
            assertEquals(id, old.now())

            // Renewed tokens are young again, also for the next process.
            old.renewIfDue()
            assertEquals(id, fixture.reopen().now())
            assertTrue(stored().endsWith(" access-2 refresh-2 ${fixture.clock.now.epochSecond}"))
            assertEquals(1, fixture.grants.size)
        }

    @Test
    fun workThatGoesOnRenewsTokensThatGrewOld() =
        test {
            val session = signedIn()
            val id = session.now()
            fixture.answer = { TokenReply.NoAnswer(DataError.NoConnection) }
            session.renewIfDue()
            assertEquals(0, fixture.grants.size)

            fixture.clock.advance(RENEW_AFTER)
            session.renewIfDue()
            assertEquals(listOf(refreshGrant("refresh-1")), fixture.grants)
            assertEquals(id, session.now())

            fixture.answer = { granted("access-2", "refresh-2") }
            session.renewIfDue()
            session.renewIfDue()
            assertEquals(2, fixture.grants.size)
            val mal = FakeMal("access-2")
            assertEquals(Outcome.Success("access-2"), session.authorised(mal.request))
            assertEquals(listOf("access-2"), mal.sent)
            assertEquals(id, session.now())
        }

    @Test
    fun oldTokensThatMalRejectsAtTheStartEndInExpired() =
        test {
            signedIn().now()
            fixture.clock.advance(RENEW_AFTER)
            val seen = CompletableDeferred<Unit>()
            fixture.answer = {
                seen.await()
                TokenReply.Answer(400, "")
            }
            fixture.reopen().observeSession().test {
                assertTrue(awaitItem() is Session.SignedIn)
                seen.complete(Unit)
                assertEquals(Session.Expired, awaitItem())
            }
            assertFalse(stored().contains("refresh-1"))
        }

    @Test
    fun aStoreThatCannotBeReadIsSignedOut() =
        test {
            fixture.file.writeBytes(byteArrayOf(1, 2, 3))
            val session = fixture.open()
            assertEquals(Session.SignedOut, session.now())
            assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access-1", "refresh-1")))
            assertTrue(fixture.reopen().now() is Session.SignedIn)
        }
}
