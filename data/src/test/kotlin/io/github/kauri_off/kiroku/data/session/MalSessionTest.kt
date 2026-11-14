package io.github.kauri_off.kiroku.data.session

import androidx.datastore.core.Serializer
import app.cash.turbine.test
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
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

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

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
            val real = fixture.cipher
            val slow =
                object : TokenCipher by real {
                    override fun open(
                        sealed: ByteArray,
                        bound: ByteArray,
                    ): ByteArray {
                        reading.countDown()
                        read.await()
                        return real.open(sealed, bound)
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
    fun everySignInIsANewOneAlsoWithTheSameTokensAfterASignOutOrAnExpiry() =
        test {
            val grant = TokenGrant("access-1", "refresh-1")
            val session = fixture.open()
            val ids = mutableListOf<Session>()
            session.signIn(grant)
            ids += session.now()
            session.signIn(grant)
            ids += session.now()
            session.signOut()
            session.signIn(grant)
            ids += session.now()
            fixture.answer = { TokenReply.Answer(400, "") }
            session.authorised(FakeMal().request)
            assertTrue(session.now() is Session.Expired)
            session.signIn(grant)
            ids += session.now()
            val next = fixture.reopen()
            assertEquals(ids.last(), next.now())
            next.signIn(grant)
            ids += next.now()

            assertTrue("$ids", ids.all { it is Session.SignedIn })
            assertEquals("$ids", ids.size, ids.toSet().size)
        }

    @Test
    fun aSignInThatCannotBeStoredSignsNobodyIn() =
        test {
            val session = fixture.open(FailingCipher(fixture.cipher, opens = false, seals = false))
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

    // All ten are sent before one is answered, and the token endpoint answers once each of them waits for it.
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
            val id = (session.now() as Session.SignedIn).signIn
            val mal = FakeMal()
            val outcomes = tenRejectedAtOnce(session, mal, TokenReply.Answer(401, ""))
            assertEquals(List(10) { expired }, outcomes)
            assertEquals(1, fixture.grants.size)
            assertEquals(List(10) { "access-1" }, mal.sent)
            assertEquals(Session.Expired(id), session.now())
        }

    @Test
    fun aRejectedRefreshEndsInExpiredWithTheTokensGone() =
        test {
            val session = signedIn()
            val mal = FakeMal()
            fixture.answer = { TokenReply.Answer(400, "") }
            val id = (session.now() as Session.SignedIn).signIn
            session.observeSession().test {
                assertEquals(Session.SignedIn(id), awaitItem())
                assertEquals(expired, session.authorised(mal.request))
                assertEquals(Session.Expired(id), awaitItem())
            }
            assertEquals(listOf("access-1"), mal.sent)

            // Nothing is sent or refreshed any more, in this process or the next.
            assertEquals(expired, session.authorised(mal.request))
            session.renewIfDue()
            assertEquals("\u0000${id.value}", fixture.file.readText())
            val next = fixture.reopen()
            assertEquals(Session.Expired(id), next.now())
            assertEquals(expired, next.authorised(mal.request))
            assertEquals(listOf("access-1"), mal.sent)
            assertEquals(1, fixture.grants.size)
        }

    @Test
    fun anExpiredSessionNamesItsSignInUntilASignOutOrANewSignIn() =
        test {
            val first = signedIn()
            val id = (first.now() as Session.SignedIn).signIn
            fixture.answer = { TokenReply.Answer(400, "") }
            first.authorised(FakeMal().request)
            assertEquals(Session.Expired(id), fixture.reopen().now())

            val signedOut = fixture.reopen()
            assertEquals(Session.Expired(id), signedOut.now())
            signedOut.signOut()
            assertEquals(Session.SignedOut, signedOut.now())
            assertEquals(0L, fixture.file.length())
            assertEquals(Session.SignedOut, fixture.reopen().now())

            // The next sign-in that expires is another one.
            val second = fixture.reopen()
            second.signIn(TokenGrant("access-2", "refresh-2"))
            val other = (second.now() as Session.SignedIn).signIn
            assertNotEquals(id, other)
            second.authorised(FakeMal().request)
            assertEquals(Session.Expired(other), second.now())
            val third = fixture.reopen()
            assertEquals(Session.Expired(other), third.now())
            third.signIn(TokenGrant("access-3", "refresh-3"))
            assertNotEquals(other, (third.now() as Session.SignedIn).signIn)
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
            lateinit var thrown: Exception
            fixture.answer = { grant -> throw IllegalStateException("could not post $grant").also { thrown = it } }
            assertEquals(Outcome.Failure(DataError.ServerError), session.authorised(FakeMal().request))
            // Its type and its stack are reported, and not its message, which holds the refresh token.
            val reported = fixture.unexpected.single()
            assertEquals("java.lang.IllegalStateException", reported.message)
            assertEquals(thrown.stackTrace.toList(), reported.stackTrace.toList())
            assertTrue("refresh-1" in thrown.message.orEmpty())
            assertFalse("refresh-1" in reported.stackTraceToString())
            assertEquals(id, session.now())
        }

    @Test
    fun anExceptionOfARequestIsReportedAndIsAnOutcome() =
        test {
            val session = signedIn()
            assertEquals(Outcome.Failure(DataError.ServerError), session.authorised<Unit> { throw IllegalStateException("sent $it") })
            assertEquals(listOf("java.lang.IllegalStateException"), fixture.reported)
            assertFalse("access-1" in fixture.unexpected.single().stackTraceToString())
            assertEquals(Outcome.Success("access-1"), session.authorised(FakeMal("access-1").request))
        }

    @Test
    fun howeverARefreshEndsTheNextRejectedRequestStartsANewOne() =
        test {
            val session = signedIn()
            val id = session.now()
            val endings =
                listOf<suspend () -> TokenReply>(
                    { TokenReply.NoAnswer(DataError.NoConnection) },
                    { TokenReply.Answer(500, "") },
                    { throw IllegalStateException("broken") },
                    { throw CancellationException("not the refresh's own") },
                    { withTimeout(1) { awaitCancellation() } },
                )
            for ((earlier, ending) in endings.withIndex()) {
                fixture.answer = { ending() }
                val outcome = session.authorised(FakeMal().request)
                assertTrue("$earlier: $outcome", outcome is Outcome.Failure && outcome.error != DataError.SessionExpired)
                assertEquals(earlier + 1, fixture.grants.size)
                assertTrue("$earlier", fixture.sessionScope.isActive)
                assertEquals(id, session.now())
            }
            // After a refresh that succeeded, too.
            for (next in 2..3) {
                fixture.answer = { granted("access-$next", "refresh-$next") }
                assertEquals(Outcome.Success("access-$next"), session.authorised(FakeMal("access-$next").request))
                assertEquals(refreshGrant("refresh-${next - 1}"), fixture.grants.last())
            }
            assertEquals(endings.size + 2, fixture.grants.size)
            assertEquals(id, session.now())
        }

    @Test
    fun aRefreshThatWasCancelledIsAnOutcomeAndTheNextRejectedRequestStartsANewOne() =
        test {
            val session = signedIn()
            val asked = CompletableDeferred<Unit>()
            fixture.answer = {
                asked.complete(Unit)
                awaitCancellation()
            }
            val waiting = async { session.authorised(FakeMal().request) }
            asked.await()
            fixture.sessionScope.coroutineContext.job.children
                .forEach { it.cancel() }
            assertEquals(Outcome.Failure(DataError.ServerError), waiting.await())
            assertTrue(fixture.sessionScope.isActive)

            fixture.answer = { granted("access-2", "refresh-2") }
            assertEquals(Outcome.Success("access-2"), session.authorised(FakeMal("access-2").request))
            assertEquals(2, fixture.grants.size)
        }

    @Test
    fun onADispatcherThatRunsAtOnceARefreshAnsweredWithoutSuspendingLeavesNothingBehind() =
        test {
            val session = fixture.open(dispatcher = Dispatchers.Unconfined)
            session.signIn(TokenGrant("access-1", "refresh-1"))
            fixture.answer = { TokenReply.NoAnswer(DataError.NoConnection) }
            assertEquals(Outcome.Failure(DataError.NoConnection), session.authorised(FakeMal().request))

            fixture.answer = { granted("access-2", "refresh-2") }
            val mal = FakeMal("access-2")
            assertEquals(Outcome.Success("access-2"), session.authorised(mal.request))
            assertEquals(listOf("access-1", "access-2"), mal.sent)
            assertEquals(2, fixture.grants.size)
        }

    @Test
    fun aRequestRejectedAfterAnothersRefreshFinishedRetriesWithTheNewTokenAndAsksForNoSecondRefresh() =
        test {
            val session = signedIn()
            val id = session.now()
            // MAL revokes a refresh token once it was used.
            fixture.answer = { if (fixture.grants.size == 1) granted("access-2", "refresh-2") else TokenReply.Answer(400, "") }
            val mal = FakeMal("access-2")
            val parked = CompletableDeferred<Unit>()
            val answered = CompletableDeferred<Unit>()
            val sent = mutableListOf<String>()
            val late =
                async {
                    session.authorised { token ->
                        sent += token
                        if (token == "access-1") {
                            parked.complete(Unit)
                            answered.await()
                        }
                        mal.request.send(token)
                    }
                }
            parked.await()
            assertEquals(Outcome.Success("access-2"), session.authorised(mal.request))
            assertEquals(1, fixture.grants.size)

            answered.complete(Unit)
            assertEquals(Outcome.Success("access-2"), late.await())
            assertEquals(listOf("access-1", "access-2"), sent)
            assertEquals(listOf(refreshGrant("refresh-1")), fixture.grants)
            assertEquals(id, session.now())
        }

    @Test
    fun aRefreshedPairThatCannotBeWrittenIsReportedAndServesThisProcess() =
        test {
            val cipher = FailingCipher(fixture.cipher)
            val session = fixture.open(cipher)
            session.signIn(TokenGrant("access-1", "refresh-1"))
            val id = session.now()
            cipher.seals = false
            fixture.answer = { granted("access-2", "refresh-2") }
            val mal = FakeMal("access-2")
            assertEquals(Outcome.Success("access-2"), session.authorised(mal.request))
            assertEquals(listOf("java.io.IOException"), fixture.reported)
            assertFalse(fixture.unexpected.single() is TokensNotRead)
            assertEquals(Outcome.Success("access-2"), session.authorised(mal.request))
            assertEquals(listOf("access-1", "access-2", "access-2"), mal.sent)
            assertEquals(1, fixture.grants.size)
            assertEquals(id, session.now())
            // The file still holds the pair MAL has replaced: that is what the report is about.
            assertTrue(stored().contains(" access-1 refresh-1 "))
        }

    // A store that works until [broken], then throws what no caller of a store expects.
    private class BreakingFormat(
        private val real: Serializer<Stored>,
        var broken: Boolean = false,
    ) : Serializer<Stored> by real {
        override suspend fun readFrom(input: InputStream): Stored =
            if (broken) throw IllegalStateException("broken") else real.readFrom(input)

        override suspend fun writeTo(
            t: Stored,
            output: OutputStream,
        ) = if (broken) throw IllegalStateException("broken") else real.writeTo(t, output)
    }

    @Test
    fun anExceptionOfTheStoreDuringARefreshIsReportedAndRefreshingGoesOn() =
        test {
            val format = BreakingFormat(fixture.format())
            val session = fixture.open(format = format)
            session.signIn(TokenGrant("access-1", "refresh-1"))
            format.broken = true
            for (next in 2..3) {
                fixture.answer = { granted("access-$next", "refresh-$next") }
                assertEquals(Outcome.Success("access-$next"), session.authorised(FakeMal("access-$next").request))
                assertEquals(refreshGrant("refresh-${next - 1}"), fixture.grants.last())
            }
            assertEquals(2, fixture.grants.size)
            assertEquals(List(2) { "java.lang.IllegalStateException" }, fixture.reported)
            assertTrue(fixture.sessionScope.isActive)
        }

    @Test
    fun aStoreThatThrowsWhenReadIsSignedOutAndReported() =
        test {
            signedIn().now()
            fixture.close()
            val session = fixture.open(format = BreakingFormat(fixture.format(), broken = true))
            assertEquals(Session.SignedOut, session.now())
            assertEquals(listOf("java.lang.IllegalStateException"), fixture.reported)
            val mal = FakeMal("access-1")
            assertEquals(expired, session.authorised(mal.request))
            assertEquals(emptyList<String>(), mal.sent)
            assertEquals(Outcome.Failure(DataError.StorageError), session.signIn(TokenGrant("access-1", "refresh-1")))
            session.signOut()
            session.renewIfDue()
            assertEquals(Session.SignedOut, session.now())
            assertTrue(fixture.sessionScope.isActive)
        }

    @Test
    fun tokensDatedInTheFutureAreDue() =
        test {
            val session = signedIn()
            val id = session.now()
            fixture.clock.advance(Duration.ofSeconds(-1))
            fixture.answer = { granted("access-2", "refresh-2") }
            session.renewIfDue()
            assertEquals(listOf(refreshGrant("refresh-1")), fixture.grants)

            // The renewed pair is dated by the clock as it is now.
            session.renewIfDue()
            fixture.clock.advance(RENEW_AFTER - Duration.ofSeconds(1))
            session.renewIfDue()
            assertEquals(1, fixture.grants.size)
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
            var returned = false
            val request =
                launch {
                    session.authorised(FakeMal().request)
                    returned = true
                }
            asked.await()
            request.cancelAndJoin()
            assertFalse(returned)
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
            val id = signedIn().now()
            fixture.clock.advance(RENEW_AFTER)
            val seen = CompletableDeferred<Unit>()
            fixture.answer = {
                seen.await()
                TokenReply.Answer(400, "")
            }
            fixture.reopen().observeSession().test {
                assertEquals(id, awaitItem())
                seen.complete(Unit)
                assertEquals(Session.Expired((id as Session.SignedIn).signIn), awaitItem())
            }
            assertFalse(stored().contains("refresh-1"))
        }

    @Test
    fun aFileThatNamesNoSignInIsSignedOut() =
        test {
            fixture.file.writeBytes(byteArrayOf(1, 2, 3))
            val session = fixture.open()
            assertEquals(Session.SignedOut, session.now())
            assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access-1", "refresh-1")))
            assertTrue(fixture.reopen().now() is Session.SignedIn)
        }

    @Test
    fun aStartThatCannotOpenTheTokensIsExpiredForItsSignInAndTheNextStartRecovers() =
        test {
            val id = (signedIn().now() as Session.SignedIn).signIn
            val file = fixture.file.readBytes().toList()
            val session = fixture.reopen(FailingCipher(fixture.cipher, opens = false))
            session.observeSession().test {
                assertEquals(Session.Expired(id), awaitItem())
                expectNoEvents()
            }
            // Nothing is sent, and the file is as it was.
            val mal = FakeMal("access-1")
            assertEquals(expired, session.authorised(mal.request))
            session.renewIfDue()
            assertEquals(emptyList<String>(), mal.sent)
            assertEquals(emptyList<Map<String, String>>(), fixture.grants)
            assertEquals(file, fixture.file.readBytes().toList())
            // Reported by its type: the message is the Keystore's own.
            assertEquals(listOf("java.security.ProviderException"), fixture.reported)
            assertFalse("no Keystore" in fixture.unexpected.single().stackTraceToString())
            assertTrue(fixture.unexpected.single() is TokensNotRead)

            val next = fixture.reopen()
            assertEquals(Session.SignedIn(id), next.now())
            assertEquals(Outcome.Success("access-1"), next.authorised(mal.request))
        }

    @Test
    fun afterAStartThatCannotOpenTheTokensASignOutOrANewSignInStillWorks() =
        test {
            val id = (signedIn().now() as Session.SignedIn).signIn
            val closed = FailingCipher(fixture.cipher, opens = false)
            val session = fixture.reopen(closed)
            assertEquals(Session.Expired(id), session.now())
            session.signIn(TokenGrant("access-2", "refresh-2"))
            val other = session.now()
            assertTrue(other is Session.SignedIn)
            assertNotEquals(Session.SignedIn(id), other)
            assertEquals(Outcome.Success("access-2"), session.authorised(FakeMal("access-2").request))

            val again = fixture.reopen(closed)
            assertEquals(Session.Expired((other as Session.SignedIn).signIn), again.now())
            again.signOut()
            assertEquals(Session.SignedOut, again.now())
            assertEquals(0L, fixture.file.length())
            assertEquals(Session.SignedOut, fixture.reopen().now())
        }

    private val soon: (Int) -> Duration = { Duration.ofMillis(10) }

    private suspend fun MalSession.known(): Session? = withTimeoutOrNull(300) { now() }

    @Test
    fun aFileThatCannotBeReadAtTheStartSignsNobodyOutAndIsReadAgainUntilItCanBe() =
        test {
            val id = signedIn().now()
            val file = fixture.file.readBytes().toList()
            fixture.close()
            val disk = FailingDisk(fixture.format())
            val session = fixture.open(format = disk, retryAfter = soon)
            assertEquals(null, session.known())
            // Nothing is sent or changed meanwhile, and the many tries are one report, of the type alone.
            val mal = FakeMal("access-1")
            assertEquals(Outcome.Failure(DataError.StorageError), session.authorised(mal.request))
            session.renewIfDue()
            assertEquals(emptyList<String>(), mal.sent)
            assertEquals(file, fixture.file.readBytes().toList())
            assertEquals(listOf("java.io.IOException"), fixture.reported)
            assertFalse("title" in fixture.unexpected.single().stackTraceToString())
            assertTrue(fixture.unexpected.single() is TokensNotRead)

            disk.failing = false
            assertEquals(id, session.now())
            assertEquals(Outcome.Success("access-1"), session.authorised(mal.request))
            assertEquals(1, fixture.reported.size)
        }

    @Test
    fun afterAStartThatCouldNotReadTheFileASignInOrASignOutStillWorks() =
        test {
            val id = signedIn().now()
            fixture.close()
            val session = fixture.open(format = FailingDisk(fixture.format()), retryAfter = soon)
            assertEquals(null, session.known())
            assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access-2", "refresh-2")))
            val other = session.now()
            assertTrue(other is Session.SignedIn)
            assertNotEquals(id, other)
            assertEquals(other, fixture.reopen().now())

            fixture.close()
            val again = fixture.open(format = FailingDisk(fixture.format()), retryAfter = soon)
            assertEquals(null, again.known())
            again.signOut()
            assertEquals(Session.SignedOut, again.now())
            assertEquals(Session.SignedOut, fixture.reopen().now())
        }

    private suspend fun untilTheFileChanged(from: List<Byte>) =
        withTimeout(10_000) {
            while (fixture.file.readBytes().toList() == from) delay(5)
        }

    @Test
    fun aRefreshedPairThatCouldNotBeWrittenIsWrittenOnceTheFileTakesIt() =
        test {
            val cipher = FailingCipher(fixture.cipher)
            val session = fixture.open(cipher, retryAfter = soon)
            session.signIn(TokenGrant("access-1", "refresh-1"))
            val file = fixture.file.readBytes().toList()
            cipher.seals = false
            fixture.answer = { granted("access-2", "refresh-2") }
            assertEquals(Outcome.Success("access-2"), session.authorised(FakeMal("access-2").request))
            delay(100)
            assertEquals(file, fixture.file.readBytes().toList())

            cipher.seals = true
            untilTheFileChanged(file)
            // The tries that failed are one report, and the next process starts with the pair MAL knows.
            assertEquals(listOf("java.io.IOException"), fixture.reported)
            assertEquals(1, fixture.grants.size)
            assertTrue(stored().contains(" access-2 refresh-2 "))
        }

    @Test
    fun aPairThatCouldNotBeWrittenIsNotWrittenOverALaterSignOut() =
        test {
            val cipher = FailingCipher(fixture.cipher)
            val session = fixture.open(cipher, retryAfter = soon)
            session.signIn(TokenGrant("access-1", "refresh-1"))
            cipher.seals = false
            fixture.answer = { granted("access-2", "refresh-2") }
            assertEquals(Outcome.Success("access-2"), session.authorised(FakeMal("access-2").request))
            session.signOut()
            cipher.seals = true
            delay(200)
            assertEquals(0L, fixture.file.length())
            assertEquals("signed out", stored())
        }

    @Test
    fun aSignOutThatCouldNotBeWrittenIsWrittenOnceTheFileTakesIt() =
        test {
            val format = BreakingFormat(fixture.format())
            val session = fixture.open(format = format, retryAfter = soon)
            session.signIn(TokenGrant("access-1", "refresh-1"))
            val file = fixture.file.readBytes().toList()
            format.broken = true
            session.signOut()
            assertEquals(Session.SignedOut, session.now())
            assertEquals(file, fixture.file.readBytes().toList())
            format.broken = false
            untilTheFileChanged(file)
            assertEquals("signed out", stored())
        }

    @Test
    fun aChildThatFailsIsReportedAndEndsNeitherTheSessionsScopeNorTheOneItWasMadeIn() =
        test {
            val parent = CoroutineScope(Job())
            val reports = CopyOnWriteArrayList<Throwable>()
            val scope = sessionScope(parent, reports::add)
            scope.launch { throw IllegalStateException("with a title") }.join()
            assertTrue(parent.isActive && scope.isActive)
            assertEquals(7, scope.async { 7 }.await())
            assertEquals(listOf("java.lang.IllegalStateException"), reports.map { it.message })
            parent.cancel()
            assertFalse(scope.isActive)
        }

    @Test
    fun theSessionOfTheAppRunsInSuchAScope() =
        test {
            val parent = CoroutineScope(Job())
            fixture.file.mkdirs()
            val session =
                malSession(fixture.file, TokenEndpoint { error("unused") }, fixture.clock, parent) { throw IllegalStateException() }
            assertTrue(runCatching { session.now() }.exceptionOrNull() is IllegalStateException)
            assertTrue(parent.isActive)
            parent.coroutineContext.job.cancelAndJoin()
        }

    @Test
    fun aSignInThatCannotBeWrittenWhileTheFileCannotBeReadLosesNeitherTheFileNorItsSignIn() =
        test {
            val id = signedIn().now()
            val file = fixture.file.readBytes().toList()
            fixture.close()
            val disk = FailingDisk(fixture.format(), fullToo = true)
            val session = fixture.open(format = disk, retryAfter = soon)
            assertEquals(null, session.known())

            assertEquals(Outcome.Failure(DataError.StorageError), session.signIn(TokenGrant("access-2", "refresh-2")))
            session.signOut()
            delay(100)
            assertEquals(file, fixture.file.readBytes().toList())
            assertEquals(
                listOf(fixture.file.name),
                fixture.file.parentFile!!
                    .list()
                    .orEmpty()
                    .toList(),
            )

            // The sign-out that the file did not take still holds; nothing of the failed sign-in does.
            assertEquals(Session.SignedOut, session.now())
            disk.failing = false
            assertEquals(id, fixture.reopen().now())
        }

    @Test
    fun theWaitsBetweenTriesOfTheFileAreASecondDoublingToAMinuteAndNoLonger() {
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 60, 60, 60), (0..8).map { fileRetry(it).seconds })
        for (attempt in listOf(9, 40, 62, 63, 64, 1000, Int.MAX_VALUE)) assertEquals("$attempt", 60, fileRetry(attempt).seconds)
    }

    @Test
    fun aValueTheFileDidNotTakeIsWrittenWhenAskedAndAFileThatHoldsItsValueIsLeftAlone() =
        test {
            val cipher = FailingCipher(fixture.cipher)
            val session = fixture.open(cipher, retryAfter = { Duration.ofDays(1) })
            session.signIn(TokenGrant("access-1", "refresh-1"))
            val first = fixture.file.readBytes().toList()
            session.writeOwed()
            assertEquals(first, fixture.file.readBytes().toList())

            cipher.seals = false
            fixture.answer = { granted("access-2", "refresh-2") }
            assertEquals(Outcome.Success("access-2"), session.authorised(FakeMal("access-2").request))
            session.writeOwed()
            assertEquals(first, fixture.file.readBytes().toList())

            cipher.seals = true
            session.writeOwed()
            val second = fixture.file.readBytes().toList()
            assertNotEquals(first, second)
            session.writeOwed()
            assertEquals(second, fixture.file.readBytes().toList())
            assertEquals(listOf("java.io.IOException"), fixture.reported)
            assertTrue(stored().contains(" access-2 refresh-2 "))
        }
}
