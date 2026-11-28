package io.github.kauri_off.kiroku.data.session

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.data.network.ReadDeferred
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
    private val owners = MutableStateFlow<SessionOwner?>(null)

    // Held by every change of the tokens, a refresh's request to MAL included.
    private val gate = Mutex()

    @Volatile
    private var signingOut = false

    // The value the file did not take yet; changed only with the gate held.
    private var owed: Stored? = null

    private val lock = Any()
    private var renewal: Renewal? = null

    private val read =
        scope.async(start = CoroutineStart.LAZY) {
            if (gate.withLock { readFile(first = true) } == null) scope.launch { readAgain() }
        }

    // Null when the file is there and could not be read; only the first such failure is reported.
    private suspend fun readFile(first: Boolean): Stored? {
        var unread: IOException? = null
        val stored =
            guarded<Stored?>(newGuest()) {
                try {
                    store.read()
                } catch (e: IOException) {
                    unread = e
                    null
                }
            }
        if (first) unread?.let { unexpected(TokensNotRead(it)) }
        return stored?.also(::publish)
    }

    // Until the file was read, or a sign-in or a sign-out took its place.
    private suspend fun readAgain() {
        var attempt = 0
        while (true) {
            delay(retryAfter(attempt++).toMillis())
            if (gate.withLock { if (state.value == null) readFile(first = false) else return } != null) return
        }
    }

    // A renewal nobody asked for is background work: it is not sent while MAL's back-off lasts (section 2.3).
    private suspend fun renewUnasked(tokens: Tokens) {
        if (!guarded(false) { endpoint.refusing() }) renew(tokens)
    }

    override fun observeSession(): Flow<Session> =
        flow {
            read.await()
            emitAll(state.filterNotNull().map { it.session }.distinctUntilChanged())
        }

    override suspend fun signOut() {
        guarded(Unit) {
            change { retiring { kept(newGuest()) } }
        }
    }

    fun observeOwner(): Flow<SessionOwner> =
        flow {
            read.await()
            emitAll(owners.filterNotNull())
        }

    internal suspend fun currentOwner(): SessionOwner? {
        read.await()
        return owners.value
    }

    internal fun isCurrentOwner(owner: SessionOwner): Boolean = owners.value === owner

    internal suspend fun <T> withOwner(
        owner: SessionOwner,
        run: suspend () -> T,
    ): T? {
        read.await()
        return gate.withLock { if (isCurrentOwner(owner)) run() else null }
    }

    private fun publish(value: Stored) {
        val id = value.session.owner
        val current = owners.value
        val guest = (value as? Stored.Guest)?.generation
        if (current == null || current.id != id || current.guestGeneration != guest) owners.value = SessionOwner(id, guest)
        state.value = value
    }

    suspend fun whileOwner(
        owner: SignInId?,
        run: suspend () -> Boolean,
    ): Boolean {
        read.await()
        return gate.withLock {
            val session = state.value?.session ?: return@withLock false
            session.owner == owner && run()
        }
    }

    suspend fun currentSession(): Session? {
        read.await()
        return state.value?.session
    }

    suspend fun signOutIfCurrent(
        owner: SignInId?,
        onWriteFailure: suspend () -> Unit = {},
        beforeSignOut: suspend () -> Unit,
    ): Boolean =
        guarded(false) {
            change {
                if (state.value?.session?.owner != owner) return@change false
                retiring {
                    beforeSignOut()
                    kept(newGuest(), onWriteFailure)
                    true
                }
            }
        }

    private suspend fun <T> retiring(run: suspend () -> T): T {
        signingOut = true
        try {
            return run()
        } finally {
            signingOut = false
        }
    }

    // A new sign-in with the tokens MAL granted; nobody is signed in when they cannot be stored.
    internal suspend fun signIn(grant: TokenGrant): Outcome<Unit> =
        guarded(Outcome.Failure(DataError.StorageError)) {
            change {
                val signedIn = Stored.SignedIn(Tokens(UUID.randomUUID().toString(), grant.access, grant.refresh, now(), grant.lifetime))
                if (written(signedIn)) {
                    owed = null
                    publish(signedIn)
                    Outcome.Success(Unit)
                } else {
                    Outcome.Failure(DataError.StorageError)
                }
            }
        }

    internal fun isSignedInAs(owner: SignInId): Boolean = state.value?.session == Session.SignedIn(owner)

    internal fun mayWriteAs(owner: SignInId): Boolean = !signingOut && isSignedInAs(owner)

    internal fun mayObserveAs(owner: SignInId): Boolean = !signingOut && state.value?.session?.owner == owner

    internal fun signedInOwner(): SessionOwner? = owners.value?.takeIf { it.id?.let(::isSignedInAs) == true }

    // S5: a rejected token leads to one refresh, shared with every request rejected beside this one, and one retry.
    internal suspend fun <T> authorised(request: AuthorisedRequest<T>): Outcome<T> = authorised(null, request)

    internal suspend fun <T> authorised(
        expectedOwner: SignInId?,
        request: AuthorisedRequest<T>,
    ): Outcome<T> =
        guarded(Outcome.Failure(DataError.ServerError)) {
            val stored = stored() ?: return unread
            if (expectedOwner != null && stored.session.owner != expectedOwner) return Outcome.Failure(DataError.ListEnded)
            val first = (stored as? Stored.SignedIn)?.tokens ?: return expired
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

    // S7: only when asked, through the way in: MAL revokes the old pair as it answers, so whoever asks stays until it has.
    internal suspend fun renewIfDue() {
        guarded(Unit) {
            val tokens = (stored() as? Stored.SignedIn)?.tokens ?: return
            if (due(tokens)) renewUnasked(tokens)
        }
    }

    // The session as it is, with nothing sent for it; null when the file could not be read.
    internal suspend fun current(): Session? = guarded(null) { stored()?.session }

    // The seconds MAL gave the access token in use; null when nobody is signed in or MAL did not say.
    internal suspend fun accessTokenLifetime(): Long? = guarded(null) { (stored() as? Stored.SignedIn)?.tokens?.lifetime }

    // One more try at a value the file did not take, for whatever is about to end the process.
    suspend fun writeOwed() {
        guarded(Unit) {
            change { owedWritten() }
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
            if (e is ReadDeferred) throw e
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
    private suspend fun kept(
        value: Stored,
        onWriteFailure: suspend () -> Unit = {},
    ) {
        owed = value.takeUnless { written(it) }
        if (owed != null) onWriteFailure()
        publish(value)
        if (owed != null) scope.launch { writeAgain(value) }
    }

    // Until the file holds [value], or another value took its place.
    private suspend fun writeAgain(value: Stored) {
        var attempt = 0
        while (true) {
            delay(retryAfter(attempt++).toMillis())
            if (gate.withLock { owed !== value || owedWritten() }) return
        }
    }

    // Reports nothing: the first failure of this value was reported.
    private suspend fun owedWritten(): Boolean {
        val value = owed ?: return true
        val written =
            try {
                store.write(value) == null
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                false
            }
        if (written) owed = null
        return written
    }

    // What the file said once no change of it is running; null when it could not be read.
    private suspend fun stored(): Stored? {
        read.await()
        return gate.withLock { state.value }
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
                is TokenResult.Granted ->
                    Stored.SignedIn(Tokens(used.signIn, answer.grant.access, answer.grant.refresh, now(), answer.grant.lifetime))
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
        fun newGuest() = Stored.Guest(UUID.randomUUID().toString())

        val expired = Outcome.Failure(DataError.SessionExpired)
        val unread = Outcome.Failure(DataError.StorageError)

        val Stored.session: Session
            get() =
                when (this) {
                    Stored.SignedOut, is Stored.Guest -> Session.SignedOut
                    is Stored.SignedIn -> Session.SignedIn(SignInId(tokens.signIn))
                    is Stored.Expired -> Session.Expired(SignInId(signIn))
                }
    }
}
