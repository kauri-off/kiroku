package io.github.kauri_off.kiroku.data.session

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.util.UUID

// What one sending of an authorised request came to.
internal sealed interface Attempt<out T> {
    data class Answered<out T>(
        val outcome: Outcome<T>,
    ) : Attempt<T>

    // MAL did not accept the access token.
    data object Rejected : Attempt<Nothing>
}

// Sends the request with the token it is given, and nothing else: a token is refreshed and the request repeated here.
internal fun interface AuthorisedRequest<out T> {
    suspend fun send(accessToken: String): Attempt<T>
}

// S7: tokens older than this are renewed, well inside the refresh token's month.
internal val RENEW_AFTER: Duration = Duration.ofDays(7)

internal const val KEY_ALIAS = "kiroku.session"

// An exception without its message, which could quote a token: its type and its stack.
internal class SessionFailure(
    cause: Exception,
) : Exception(cause.javaClass.name) {
    init {
        stackTrace = cause.stackTrace
    }
}

// The waits between tries of a token file that could not be read or written: a second, doubling to a minute.
internal fun fileRetry(attempt: Int): Duration = Duration.ofSeconds(minOf(1L shl attempt.coerceIn(0, 6), 60))

// The session's own scope: a child that fails is reported, and ends neither the others nor the scope it was made in.
internal fun sessionScope(
    parent: CoroutineScope,
    unexpected: (Throwable) -> Unit,
): CoroutineScope =
    parent + SupervisorJob(parent.coroutineContext[Job]) +
        CoroutineExceptionHandler { _, e -> if (e is Exception) unexpected(SessionFailure(e)) else throw e }

// The session over [file], which no other object may read or write, sealed with a key from the Android Keystore.
internal fun malSession(
    file: File,
    endpoint: TokenEndpoint,
    clock: Clock,
    scope: CoroutineScope,
    unexpected: (Throwable) -> Unit,
): MalSession {
    val own = sessionScope(scope, unexpected)
    val files = own + Dispatchers.IO + SupervisorJob(own.coroutineContext[Job])
    return MalSession(
        TokenStore(file, TokenFormat(AesGcmCipher(KeystoreKeys(KEY_ALIAS)), file.name, unexpected), files),
        endpoint,
        clock,
        own,
        unexpected,
    )
}

// The one owner of the tokens in its process: every authorised request and every change of the tokens goes through it.
class MalSession internal constructor(
    private val store: TokenStore,
    private val endpoint: TokenEndpoint,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val unexpected: (Throwable) -> Unit,
    private val retryAfter: (attempt: Int) -> Duration = ::fileRetry,
) : SessionRepository {
    private class Renewal(
        val tokens: Tokens,
        val result: Deferred<Outcome<Stored>>,
    )

    // Null until the token file was read: a file that cannot be read signs nobody out.
    private val state = MutableStateFlow<Stored?>(null)

    // Held by every change of the tokens, a refresh's request to MAL included.
    private val gate = Mutex()

    private val lock = Any()
    private var renewal: Renewal? = null

    private val read =
        scope.async(start = CoroutineStart.LAZY) {
            when (val stored = gate.withLock { readFile(first = true) }) {
                null -> scope.launch { readAgain() }
                else -> renewAtStart(stored)
            }
        }

    // Null when the file is there and could not be read; only the first such failure is reported.
    private suspend fun readFile(first: Boolean): Stored? {
        var unread: IOException? = null
        val stored =
            guarded<Stored?>(Stored.SignedOut) {
                try {
                    store.read()
                } catch (e: IOException) {
                    unread = e
                    null
                }
            }
        if (first) unread?.let { unexpected(SessionFailure(it)) }
        return stored?.also { state.value = it }
    }

    // Until the file was read, or a sign-in or a sign-out took its place.
    private suspend fun readAgain() {
        var attempt = 0
        while (true) {
            delay(retryAfter(attempt++).toMillis())
            val stored = gate.withLock { if (state.value == null) readFile(first = false) else return } ?: continue
            return renewAtStart(stored)
        }
    }

    private fun renewAtStart(stored: Stored) {
        if (stored is Stored.SignedIn && due(stored.tokens)) scope.launch { renew(stored.tokens) }
    }

    override fun observeSession(): Flow<Session> =
        flow {
            read.await()
            emitAll(state.filterNotNull().map { it.session }.distinctUntilChanged())
        }

    override suspend fun signOut() {
        guarded(Unit) {
            change { kept(Stored.SignedOut) }
        }
    }

    // A new sign-in with the tokens MAL granted; nobody is signed in when they cannot be stored.
    internal suspend fun signIn(grant: TokenGrant): Outcome<Unit> =
        guarded(Outcome.Failure(DataError.StorageError)) {
            change {
                val signedIn = Stored.SignedIn(Tokens(UUID.randomUUID().toString(), grant.access, grant.refresh, now()))
                if (written(signedIn)) {
                    state.value = signedIn
                    Outcome.Success(Unit)
                } else {
                    Outcome.Failure(DataError.StorageError)
                }
            }
        }

    // S5: a rejected token leads to one refresh, shared with every request rejected beside this one, and one retry.
    internal suspend fun <T> authorised(request: AuthorisedRequest<T>): Outcome<T> =
        guarded(Outcome.Failure(DataError.ServerError)) {
            val first = tokens() ?: return expired
            when (val attempt = request.send(first.access)) {
                is Attempt.Answered -> return attempt.outcome
                Attempt.Rejected -> Unit
            }
            val second =
                when (val renewed = renew(first)) {
                    is Outcome.Failure -> return renewed
                    is Outcome.Success ->
                        (renewed.value as? Stored.SignedIn)?.tokens?.takeIf { it.signIn == first.signIn } ?: return expired
                }
            when (val attempt = request.send(second.access)) {
                is Attempt.Answered -> attempt.outcome
                Attempt.Rejected -> expired
            }
        }

    // S7: for whatever starts work without a screen; the session itself does it when it is first read.
    suspend fun renewIfDue() {
        guarded(Unit) {
            val tokens = tokens() ?: return
            if (due(tokens)) renew(tokens)
        }
    }

    private fun now(): Long = clock.instant().epochSecond

    // Tokens dated in the future were dated by a clock that was wrong, and are due.
    private fun due(tokens: Tokens): Boolean = (now() - tokens.obtainedAt).let { it < 0 || it >= RENEW_AFTER.seconds }

    // Whatever [block] throws is reported and becomes [otherwise], unless the caller itself was cancelled.
    private suspend inline fun <T> guarded(
        otherwise: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            unexpected(SessionFailure(e))
            otherwise
        }

    // A value that was not written is reported: the next process starts with what the file still holds.
    private suspend fun written(value: Stored): Boolean =
        guarded(false) {
            val failure = store.write(value)
            if (failure != null) unexpected(SessionFailure(failure))
            failure == null
        }

    // The session is [value] from now on; a file that did not take it is written again until it does.
    private suspend fun kept(value: Stored) {
        val written = written(value)
        state.value = value
        if (!written) scope.launch { writeAgain(value) }
    }

    // Until the file holds [value], or another value took its place; these tries report nothing.
    private suspend fun writeAgain(value: Stored) {
        var attempt = 0
        while (true) {
            delay(retryAfter(attempt++).toMillis())
            val settled =
                gate.withLock {
                    state.value !== value ||
                        try {
                            store.write(value) == null
                        } catch (e: Exception) {
                            if (e is CancellationException) currentCoroutineContext().ensureActive()
                            false
                        }
                }
            if (settled) return
        }
    }

    // The tokens once no change of them is running.
    private suspend fun tokens(): Tokens? {
        read.await()
        return (gate.withLock { state.value } as? Stored.SignedIn)?.tokens
    }

    // Outlives its caller: a sign-in or sign-out that has begun is finished and stored.
    private suspend fun <T> change(block: suspend () -> T): T {
        read.await()
        return scope.async { gate.withLock { block() } }.await()
    }

    // The session after [used] were refreshed, by this call or by the one already running for them.
    private suspend fun renew(used: Tokens): Outcome<Stored> {
        val running = synchronized(lock) { renewal?.takeIf { it.tokens === used }?.result ?: registered(used) }
        running.start()
        return running.await()
    }

    // Registered before it starts, and gone from there however it ends.
    private fun registered(used: Tokens): Deferred<Outcome<Stored>> {
        val refresh = scope.async(start = CoroutineStart.LAZY) { gate.withLock { refresh(used) } }
        renewal = Renewal(used, refresh)
        refresh.invokeOnCompletion { synchronized(lock) { if (renewal?.result === refresh) renewal = null } }
        return refresh
    }

    // S6: only MAL's rejection ends the session. Tokens that a refresh or a sign-in has replaced are not refreshed.
    private suspend fun refresh(used: Tokens): Outcome<Stored> {
        val now = checkNotNull(state.value)
        if ((now as? Stored.SignedIn)?.tokens !== used) return Outcome.Success(now)
        val next =
            when (val answer = ask(refreshGrant(used.refresh))) {
                is TokenResult.Granted -> Stored.SignedIn(Tokens(used.signIn, answer.grant.access, answer.grant.refresh, now()))
                TokenResult.Rejected -> Stored.Expired(used.signIn)
                is TokenResult.Failed -> return Outcome.Failure(answer.error)
            }
        // New tokens that cannot be stored still serve this process.
        kept(next)
        return Outcome.Success(next)
    }

    private suspend fun ask(grant: Map<String, String>): TokenResult =
        guarded(TokenResult.Failed(DataError.ServerError)) { endpoint.post(grant).read() }

    private companion object {
        val expired = Outcome.Failure(DataError.SessionExpired)

        val Stored.session: Session
            get() =
                when (this) {
                    Stored.SignedOut -> Session.SignedOut
                    is Stored.SignedIn -> Session.SignedIn(SignInId(tokens.signIn))
                    is Stored.Expired -> Session.Expired(SignInId(signIn))
                }
    }
}
