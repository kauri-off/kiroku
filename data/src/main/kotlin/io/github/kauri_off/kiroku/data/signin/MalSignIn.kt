package io.github.kauri_off.kiroku.data.signin

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.repository.SignIn
import io.github.kauri_off.kiroku.core.repository.SignInBrowser
import io.github.kauri_off.kiroku.core.repository.SignInFailure
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.MalTokenEndpoint
import io.github.kauri_off.kiroku.data.session.SessionFailure
import io.github.kauri_off.kiroku.data.session.TokenEndpoint
import io.github.kauri_off.kiroku.data.session.TokenGrant
import io.github.kauri_off.kiroku.data.session.TokenResult
import io.github.kauri_off.kiroku.data.session.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

// S3: a redirect is taken only for a sign-in started no longer ago than this.
internal val STARTED_WITHIN: Duration = Duration.ofMinutes(10)

// The sign-in over [file], which no other object may read or write; [redirectUri] is the build's own, registered with MAL.
fun malSignIn(
    file: File,
    redirectUri: String,
    http: MalHttp,
    session: MalSession,
    clock: Clock,
    scope: CoroutineScope,
    unexpected: (Throwable) -> Unit,
    onFailure: ((SignInFailure) -> Unit)? = null,
): MalSignIn =
    MalSignIn(
        authorize = http.hosts.signIn + "authorize",
        clientId = http.clientId,
        redirectUri = redirectUri,
        store = PendingStore(file, scope + Dispatchers.IO + SupervisorJob(scope.coroutineContext[Job])),
        endpoint = MalTokenEndpoint(http),
        signIn = session::signIn,
        clock = clock,
        scope = scope,
        unexpected = unexpected,
        onFailure = onFailure,
    )

// MAL's authorization code grant with PKCE; its work runs in [scope], so it outlives the screen that asked for it.
class MalSignIn internal constructor(
    private val authorize: String,
    private val clientId: String,
    private val redirectUri: String,
    private val store: PendingStore,
    private val endpoint: TokenEndpoint,
    private val signIn: suspend (TokenGrant) -> Outcome<Unit>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val unexpected: (Throwable) -> Unit,
    private val onFailure: ((SignInFailure) -> Unit)? = null,
    private val random: SecureRandom = SecureRandom(),
) : SignIn {
    private val state = MutableStateFlow<SignInStep>(SignInStep.None)
    override val step = state.asStateFlow()

    // One start or redirect at a time, in the order they came.
    private val turns = Mutex()
    private val arrivals = AtomicLong()

    // Redirects up to this one came before a sign-in that was taken had ended, and say nothing after it.
    private var settled = 0L

    override suspend fun start(browser: SignInBrowser) {
        state.update { if (it is SignInStep.Failed) SignInStep.None else it }
        if (clientId.isBlank()) return failed(SignInFailure.Unavailable)
        val address = scope.async { turns.withLock { guarded(null) { begun() } } }.await() ?: return failed(SignInFailure.NotStored)
        if (!guarded(false) { browser.open(address) }) failed(SignInFailure.NoBrowser)
    }

    override fun redirected(uri: String) {
        val arrival = arrivals.incrementAndGet()
        state.update { if (it == SignInStep.SignedIn) it else SignInStep.Completing }
        scope.launch { turns.withLock { taken(uri, arrival) } }
    }

    override fun acknowledge(step: SignInStep) {
        if (step is SignInStep.Failed || step == SignInStep.SignedIn) state.compareAndSet(step, SignInStep.None)
    }

    // The address of MAL's page for a new sign-in, which replaces one that waited; null when it could not be stored.
    private suspend fun begun(): String? {
        val pending = PendingSignIn(state = text(STATE_BYTES), verifier = text(VERIFIER_BYTES), startedAt = now())
        if (!store.write(pending)) return null
        return authorize
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("state", pending.state)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge", pending.verifier)
            .addQueryParameter("code_challenge_method", "plain")
            .build()
            .toString()
    }

    private suspend fun taken(
        uri: String,
        arrival: Long,
    ) {
        when (val redirect = guarded(Refused(SignInFailure.NotStored)) { checked(uri) }) {
            is Refused -> if (arrival > settled) failed(redirect.reason)
            is Accepted -> {
                state.value = SignInStep.Completing
                val reason = guarded(SignInFailure.NoTokens) { exchanged(redirect) }
                settled = arrivals.get()
                if (reason == null) state.value = SignInStep.SignedIn else ended(reason)
            }
        }
    }

    // S3: the answer to the sign-in that waits, started a short time ago, with its state, and taken once.
    private suspend fun checked(uri: String): Redirect {
        val values = redirectValues(uri, redirectUri) ?: return Refused(SignInFailure.Unreadable)
        val pending = store.read() ?: return Refused(SignInFailure.Unasked)
        if (now() - pending.startedAt !in 0..STARTED_WITHIN.seconds) {
            store.write(null)
            return Refused(SignInFailure.Old)
        }
        if (!same(values["state"], pending.state)) return Refused(SignInFailure.WrongState)
        if (!store.write(null)) return Refused(SignInFailure.NotStored)
        val code = values["code"]?.takeIf { it.isNotBlank() && "error" !in values } ?: return Refused(SignInFailure.Denied)
        return Accepted(code, pending.verifier)
    }

    // S4: signed in only with both tokens of a successful answer, stored; null when that is so.
    private suspend fun exchanged(redirect: Accepted): SignInFailure? {
        val grant =
            mapOf(
                "grant_type" to "authorization_code",
                "code" to redirect.code,
                "code_verifier" to redirect.verifier,
                "redirect_uri" to redirectUri,
            )
        return when (val result = endpoint.post(grant).read()) {
            is TokenResult.Granted ->
                when (signIn(result.grant)) {
                    is Outcome.Success -> null
                    is Outcome.Failure -> SignInFailure.NotStored
                }
            TokenResult.Rejected -> SignInFailure.Rejected
            is TokenResult.Failed -> SignInFailure.NoTokens
        }
    }

    // A redirect that is refused never hides a sign-in that has just succeeded.
    private fun failed(reason: SignInFailure) {
        state.update { if (it == SignInStep.SignedIn) it else SignInStep.Failed(reason) }
        onFailure?.invoke(reason)
    }

    private fun ended(reason: SignInFailure) {
        state.value = SignInStep.Failed(reason)
        onFailure?.invoke(reason)
    }

    private fun now(): Long = clock.instant().epochSecond

    private fun text(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private fun same(
        given: String?,
        expected: String,
    ): Boolean = given != null && MessageDigest.isEqual(given.encodeToByteArray(), expected.encodeToByteArray())

    // Whatever [block] throws is reported without its message and becomes [otherwise], unless the caller was cancelled.
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

    private sealed interface Redirect

    private class Refused(
        val reason: SignInFailure,
    ) : Redirect

    private class Accepted(
        val code: String,
        val verifier: String,
    ) : Redirect

    private companion object {
        // 43 and 86 characters of the unreserved set; MAL takes a verifier of 43 to 128.
        const val STATE_BYTES = 32
        const val VERIFIER_BYTES = 64
    }
}

// What this build's redirect brought back, each name once; null when [uri] is another address or cannot be read.
internal fun redirectValues(
    uri: String,
    redirectUri: String,
): Map<String, String>? {
    val address = uri.substringBefore('#').substringBefore('?')
    if (address != redirectUri && address != "$redirectUri/") return null
    val query = uri.substringBefore('#').substringAfter('?', "")
    return try {
        query
            .split('&')
            .filter { it.isNotEmpty() }
            .groupBy({ decoded(it.substringBefore('=')) }, { decoded(it.substringAfter('=', "")) })
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun decoded(part: String): String = URLDecoder.decode(part, "UTF-8")
