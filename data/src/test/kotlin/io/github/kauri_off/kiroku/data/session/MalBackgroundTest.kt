package io.github.kauri_off.kiroku.data.session

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.MalBackOff
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.Thing
import io.github.kauri_off.kiroku.data.network.Things
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// The way in to MAL of work without a screen, over the app's client and session, against local servers in MAL's place.
class MalBackgroundTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    private val sessions by lazy { SessionFixture(folder.newFolder()) }

    // What the API answers from now on, and the access token of each request it received.
    private var api: () -> MockResponse = { answer() }
    private val bearers = CopyOnWriteArrayList<String>()

    // What the token endpoint answers from now on, and the form of each request it received.
    private var token: () -> MockResponse = { answer(body = tokenAnswer("access-2", "refresh-2")) }
    private val grants = CopyOnWriteArrayList<String>()

    private val ran = AtomicInteger()

    // One process's client, session and way in, over the files every process of the test shares.
    private inner class Process(
        val http: MalHttp,
        val session: MalSession,
    ) {
        private val things by http.api(Things::class)
        val background = MalBackground(http, session)

        // What a screen asks for: as the signed-in user, and through no way in.
        suspend fun read(): Outcome<Thing> = session.authorised(http) { things.readAs(it) }

        suspend fun inBackground(): BackgroundRun<Outcome<Thing>> =
            background.run {
                ran.incrementAndGet()
                read()
            }
    }

    private suspend fun newProcess(open: () -> MalSession = { sessions.open() }): Process {
        sessions.close()
        val http = network.newProcess()
        sessions.over(MalTokenEndpoint(http))
        return Process(http, open())
    }

    private suspend fun signedIn(): Process {
        val process = newProcess()
        assertEquals(Outcome.Success(Unit), process.session.signIn(TokenGrant("access-1", "refresh-1")))
        return process
    }

    // Until everything the session of the last process started by itself has ended.
    private suspend fun settle() =
        sessions.sessionScope.coroutineContext.job.children
            .forEach { it.join() }

    private fun pass(time: Duration) {
        network.clock.now += time
        sessions.clock.advance(time)
    }

    private fun refusal(retryAfter: Duration? = null): () -> MockResponse =
        {
            MockResponse
                .Builder()
                .code(429)
                .apply { if (retryAfter != null) setHeader("Retry-After", retryAfter.seconds.toString()) }
                .build()
        }

    private val refused = BackgroundRun.Ran(Outcome.Failure(DataError.Refused))
    private val read = BackgroundRun.Ran(Outcome.Success(Thing(7)))

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    @Before
    fun serve() {
        network.api.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    bearers += request.headers[AUTHORIZATION].orEmpty()
                    return api()
                }
            }
        network.signIn.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    grants += request.body?.utf8().orEmpty()
                    return token()
                }
            }
    }

    @After
    fun close() = runBlocking { sessions.close() }

    @Test
    fun `signed in, with young tokens and MAL not refusing, the work runs and nothing else is sent`() =
        test {
            assertEquals(read, signedIn().inBackground())
            assertEquals(listOf("Bearer access-1"), bearers)
            assertEquals(emptyList<String>(), grants)
            assertFalse(network.backOffFile.exists())
        }

    @Test
    fun `a refusal in one process holds back the work of a new one over the same files, for no longer than the wait that was set`() =
        test {
            api = refusal(Duration.ofHours(1))
            assertEquals(refused, signedIn().inBackground())

            assertEquals(HeldBack.Refused(Duration.ofHours(1)), newProcess().inBackground())
            pass(Duration.ofMinutes(59))
            assertEquals(HeldBack.Refused(Duration.ofMinutes(1)), newProcess().inBackground())
            assertEquals(1, ran.get())
            assertEquals(1, bearers.size)

            pass(Duration.ofMinutes(1))
            api = { answer() }
            assertEquals(read, newProcess().inBackground())
            assertEquals(2, bearers.size)
            assertFalse(network.backOffFile.exists())
            assertEquals(read, newProcess().inBackground())
            assertEquals(emptyList<String>(), grants)
        }

    @Test
    fun `a clock set back does not hold the work of a new process back longer than the wait that was set`() =
        test {
            api = refusal()
            assertEquals(refused, signedIn().inBackground())
            pass(Duration.ofMinutes(5))
            pass(Duration.ofDays(-1))

            assertEquals(HeldBack.Refused(MalBackOff.First), newProcess().inBackground())
            pass(MalBackOff.First.minusSeconds(1))
            assertEquals(HeldBack.Refused(Duration.ofSeconds(1)), newProcess().inBackground())
            assertEquals(1, bearers.size)

            pass(Duration.ofSeconds(1))
            api = { answer() }
            assertEquals(read, newProcess().inBackground())
        }

    @Test
    fun `tokens older than the renewal age are renewed before the work, once for work started together`() =
        test {
            val process = signedIn()
            val signIn = process.session.observeSession().first()
            pass(RENEW_AFTER)
            token = {
                answer(body = tokenAnswer("access-2", "refresh-2")).newBuilder().headersDelay(300, TimeUnit.MILLISECONDS).build()
            }

            val runs = (1..10).map { async(Dispatchers.Default) { process.inBackground() } }.awaitAll()

            assertEquals(List(10) { read }, runs)
            assertEquals(
                listOf("client_id=${NetworkFixture.CLIENT_ID}&grant_type=refresh_token&refresh_token=refresh-1"),
                grants.map(::sorted),
            )
            assertEquals(List(10) { "Bearer access-2" }, bearers)
            assertEquals(signIn, process.session.observeSession().first())

            // Renewed tokens are young, for the next process too.
            assertEquals(read, process.inBackground())
            assertEquals(read, newProcess().inBackground())
            assertEquals(1, grants.size)
        }

    @Test
    fun `tokens younger than the renewal age are not renewed`() =
        test {
            val process = signedIn()
            pass(RENEW_AFTER.minusSeconds(1))
            assertEquals(read, process.inBackground())
            assertEquals(emptyList<String>(), grants)
            assertEquals(listOf("Bearer access-1"), bearers)
        }

    @Test
    fun `a renewal that fails for a while keeps the tokens, and the work runs with them`() =
        test {
            val process = signedIn()
            pass(RENEW_AFTER)
            token = { answer(503) }
            assertEquals(read, process.inBackground())
            assertEquals(listOf("Bearer access-1"), bearers)
            assertTrue(process.session.observeSession().first() is Session.SignedIn)
        }

    @Test
    fun `a renewal that MAL rejects ends the session, and the work of every process after it sends nothing`() =
        test {
            val process = signedIn()
            pass(RENEW_AFTER)
            val rejected = RecordedAnswers.named("refresh_invalid")
            token = { answer(rejected.status, rejected.body) }

            assertEquals(HeldBack.Expired, process.inBackground())
            assertEquals(HeldBack.Expired, process.inBackground())
            assertEquals(HeldBack.Expired, newProcess().inBackground())
            assertEquals(0, ran.get())
            assertEquals(emptyList<String>(), bearers)
            assertEquals(1, grants.size)
        }

    @Test
    fun `with nobody signed in the work sends nothing`() =
        test {
            val process = newProcess()
            assertEquals(HeldBack.SignedOut, process.inBackground())
            process.session.signIn(TokenGrant("access-1", "refresh-1"))
            process.session.signOut()
            pass(RENEW_AFTER)
            assertEquals(HeldBack.SignedOut, process.inBackground())
            assertEquals(HeldBack.SignedOut, newProcess().inBackground())
            assertEquals(0, ran.get())
            assertEquals(0, network.api.requestCount + network.signIn.requestCount)
        }

    @Test
    fun `with a token file that cannot be read the work sends nothing and nobody is signed out`() =
        test {
            signedIn()
            val disk = FailingDisk(sessions.format())
            val process = newProcess { sessions.open(format = disk, retryAfter = { Duration.ofMillis(10) }) }

            assertEquals(HeldBack.TokensUnread, process.inBackground())
            assertEquals(0, ran.get())
            assertEquals(0, network.api.requestCount + network.signIn.requestCount)

            disk.failing = false
            assertTrue(process.session.observeSession().first() is Session.SignedIn)
            assertEquals(read, process.inBackground())
        }

    @Test
    fun `with MAL refusing, the work sends nothing, a renewal that is due included`() =
        test {
            val process = signedIn()
            pass(RENEW_AFTER)
            api = refusal()
            assertEquals(Outcome.Failure(DataError.Refused), process.read())

            assertEquals(HeldBack.Refused(MalBackOff.First), process.inBackground())
            assertEquals(HeldBack.Refused(MalBackOff.First), newProcess().inBackground())
            settle()
            assertEquals(0, ran.get())
            assertEquals(1, bearers.size)
            assertEquals(emptyList<String>(), grants)

            // The tokens are renewed by the first work after the wait.
            pass(MalBackOff.First)
            api = { answer() }
            assertEquals(read, newProcess().inBackground())
            assertEquals(1, grants.size)
            assertEquals("Bearer access-2", bearers.last())
        }

    @Test
    fun `a token endpoint that refuses the renewal holds the work back`() =
        test {
            val process = signedIn()
            pass(RENEW_AFTER)
            token = refusal()

            assertEquals(HeldBack.Refused(MalBackOff.First), process.inBackground())
            assertEquals(HeldBack.Refused(MalBackOff.First), newProcess().inBackground())
            assertEquals(0, ran.get())
            assertEquals(emptyList<String>(), bearers)
            assertEquals(1, grants.size)
            assertTrue(process.session.observeSession().first() is Session.SignedIn)
        }

    // An expired session will not come back and a wait will end, so work is told of the session first.
    @Test
    fun `while MAL is refusing, an expired session is still said to be expired and a signed-out one signed out`() =
        test {
            val process = signedIn()
            api = refusal(Duration.ofHours(1))
            assertEquals(Outcome.Failure(DataError.Refused), process.read())
            assertEquals(HeldBack.Refused(Duration.ofHours(1)), process.inBackground())

            // MAL takes neither token of a screen's request: that ends the session and not the back-off.
            val invalid = RecordedAnswers.named("token_invalid")
            val rejected = RecordedAnswers.named("refresh_invalid")
            api = { answer(invalid.status, invalid.body) }
            token = { answer(rejected.status, rejected.body) }
            assertEquals(Outcome.Failure(DataError.SessionExpired), process.read())
            assertEquals(Duration.ofHours(1), process.http.refusedFor())
            assertEquals(HeldBack.Expired, process.inBackground())
            assertEquals(HeldBack.Expired, newProcess().inBackground())

            val next = newProcess()
            next.session.signOut()
            assertEquals(Duration.ofHours(1), next.http.refusedFor())
            assertEquals(HeldBack.SignedOut, next.inBackground())
            assertEquals(HeldBack.SignedOut, newProcess().inBackground())
            assertEquals(0, ran.get())
        }

    @Test
    fun `an answer to a screen that is not a success leaves the work held back`() =
        test {
            api = refusal(Duration.ofHours(1))
            assertEquals(refused, signedIn().inBackground())

            for ((code, error) in listOf(404 to DataError.NotFound, 400 to DataError.InvalidRequest, 500 to DataError.ServerError)) {
                val process = newProcess()
                api = { answer(code) }
                assertEquals(Outcome.Failure(error), process.read())
                assertEquals("$code", HeldBack.Refused(Duration.ofHours(1)), process.inBackground())
                assertEquals("$code", HeldBack.Refused(Duration.ofHours(1)), newProcess().inBackground())
            }
            assertEquals(1, ran.get())
            assertTrue(network.backOffFile.exists())
        }

    @Test
    fun `a new process whose tokens are due renews nothing by reading its session, during a refusal or after it, until work asks`() =
        test {
            val first = signedIn()
            pass(RENEW_AFTER)
            api = refusal()
            assertEquals(Outcome.Failure(DataError.Refused), first.read())

            val process = newProcess()
            assertTrue(process.session.observeSession().first() is Session.SignedIn)
            settle()
            assertEquals(emptyList<String>(), grants)

            // Once the wait is over, reading the session still sends nothing; the way in renews.
            pass(MalBackOff.First)
            api = { answer() }
            val next = newProcess()
            assertTrue(next.session.observeSession().first() is Session.SignedIn)
            settle()
            assertEquals(emptyList<String>(), grants)
            assertTrue(next.inBackground() is BackgroundRun.Ran)
            assertEquals(1, grants.size)
        }

    @Test
    fun `what a screen asks for during the back-off is still sent, and its answer lets the work run again`() =
        test {
            api = refusal(Duration.ofHours(1))
            assertEquals(refused, signedIn().inBackground())

            // Refused again inside the wait: the same wait from now on, not a longer one.
            pass(Duration.ofMinutes(10))
            val process = newProcess()
            assertEquals(Outcome.Failure(DataError.Refused), process.read())
            assertEquals(2, bearers.size)
            assertEquals(HeldBack.Refused(Duration.ofHours(1)), process.inBackground())

            api = { answer() }
            val next = newProcess()
            assertEquals(Outcome.Success(Thing(7)), next.read())
            assertEquals(3, bearers.size)
            assertEquals(read, next.inBackground())
            assertEquals(read, newProcess().inBackground())
            assertFalse(network.backOffFile.exists())
        }

    private fun sorted(form: String): String = form.split('&').sorted().joinToString("&")
}
