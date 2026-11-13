package io.github.kauri_off.kiroku.data.session

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

// The session over [file], which no other object may read or write, sealed with a key from the Android Keystore.
internal fun malSession(
    file: File,
    endpoint: TokenEndpoint,
    clock: Clock,
    scope: CoroutineScope,
    unexpected: (Throwable) -> Unit,
): MalSession {
    val files = scope + Dispatchers.IO + SupervisorJob(scope.coroutineContext[Job])
    return MalSession(TokenStore(file, TokenFormat(AesGcmCipher { keystoreKey(KEY_ALIAS) }), files), endpoint, clock, scope, unexpected)
}

// The one owner of the tokens in its process: every authorised request and every change of the tokens goes through it.
class MalSession internal constructor(
    private val store: TokenStore,
    private val endpoint: TokenEndpoint,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val unexpected: (Throwable) -> Unit,
) : SessionRepository {
    private class Renewal(
        val tokens: Tokens,
        val result: Deferred<Outcome<Stored>>,
    )

    // Null until the token store was read.
    private val state = MutableStateFlow<Stored?>(null)

    // Held by every change of the tokens, a refresh's request to MAL included.
    private val gate = Mutex()

    private val lock = Any()
    private var renewal: Renewal? = null

    private val read =
        scope.async(start = CoroutineStart.LAZY) {
            val stored = gate.withLock { store.read().also(::set) }
            if (stored is Stored.SignedIn && due(stored.tokens)) scope.launch { renew(stored.tokens) }
        }

    override fun observeSession(): Flow<Session> =
        flow {
            read.await()
            emitAll(state.filterNotNull().map { it.session }.distinctUntilChanged())
        }

    override suspend fun signOut() {
        change {
            store.write(Stored.SignedOut)
            set(Stored.SignedOut)
        }
    }

    // A new sign-in with the tokens MAL granted; nobody is signed in when they cannot be stored.
    internal suspend fun signIn(grant: TokenGrant): Outcome<Unit> =
        change {
            val signedIn = Stored.SignedIn(Tokens(UUID.randomUUID().toString(), grant.access, grant.refresh, now()))
            if (store.write(signedIn)) {
                set(signedIn)
                Outcome.Success(Unit)
            } else {
                Outcome.Failure(DataError.StorageError)
            }
        }

    // S5: a rejected token leads to one refresh, shared with every request rejected beside this one, and one retry.
    internal suspend fun <T> authorised(request: AuthorisedRequest<T>): Outcome<T> {
        val first = tokens() ?: return expired
        when (val attempt = request.send(first.access)) {
            is Attempt.Answered -> return attempt.outcome
            Attempt.Rejected -> Unit
        }
        val second =
            when (val renewed = renew(first)) {
                is Outcome.Failure -> return renewed
                is Outcome.Success -> (renewed.value as? Stored.SignedIn)?.tokens?.takeIf { it.signIn == first.signIn } ?: return expired
            }
        return when (val attempt = request.send(second.access)) {
            is Attempt.Answered -> attempt.outcome
            Attempt.Rejected -> expired
        }
    }

    // S7: for whatever starts work without a screen; the session itself does it when it is first read.
    suspend fun renewIfDue() {
        val tokens = tokens() ?: return
        if (due(tokens)) renew(tokens)
    }

    private fun now(): Long = clock.instant().epochSecond

    private fun due(tokens: Tokens): Boolean = now() - tokens.obtainedAt >= RENEW_AFTER.seconds

    private fun set(stored: Stored) = synchronized(lock) { state.value = stored }

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
        val result =
            synchronized(lock) {
                val now = checkNotNull(state.value)
                val running = renewal
                when {
                    (now as? Stored.SignedIn)?.tokens !== used -> CompletableDeferred(Outcome.Success(now))
                    running?.tokens === used -> running.result
                    else -> scope.async { gate.withLock { refresh(used) } }.also { renewal = Renewal(used, it) }
                }
            }
        return result.await()
    }

    // S6: only MAL's rejection ends the session. New tokens that cannot be stored still serve this process.
    private suspend fun refresh(used: Tokens): Outcome<Stored> {
        val now = checkNotNull(state.value)
        val result =
            if ((now as? Stored.SignedIn)?.tokens !== used) {
                Outcome.Success(now)
            } else {
                when (val answer = ask(refreshGrant(used.refresh))) {
                    is TokenResult.Granted ->
                        Outcome.Success(Stored.SignedIn(Tokens(used.signIn, answer.grant.access, answer.grant.refresh, now())))
                    TokenResult.Rejected -> Outcome.Success(Stored.Expired(used.signIn))
                    is TokenResult.Failed -> Outcome.Failure(answer.error)
                }
            }
        if (result is Outcome.Success && result.value !== now) store.write(result.value)
        synchronized(lock) {
            if (result is Outcome.Success) state.value = result.value
            if (renewal?.tokens === used) renewal = null
        }
        return result
    }

    private suspend fun ask(grant: Map<String, String>): TokenResult =
        try {
            endpoint.post(grant).read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unexpected(e)
            TokenResult.Failed(DataError.ServerError)
        }

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
