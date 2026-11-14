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
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import kotlin.math.abs

// S3: a redirect is taken only for a sign-in started no longer ago than this.
internal val STARTED_WITHIN: Duration = Duration.ofMinutes(10)

// The token request is given up after this, longer than the network layer's own time-outs taken together.
internal val EXCHANGE_WITHIN: Duration = Duration.ofSeconds(60)

// The sign-in of the session kept in [sessionFile]; [redirectUri] is the build's own, registered with MAL.
fun malSignIn(
    sessionFile: File,
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
        store = PendingStore(pendingFile(sessionFile), scope + Dispatchers.IO + SupervisorJob(scope.coroutineContext[Job])),
        endpoint = MalTokenEndpoint(http),
        signIn = session::signIn,
        clock = clock,
        scope = scope,
        unexpected = unexpected,
        onFailure = onFailure,
    )

// The waiting sign-in is kept beside its session's file and named after it, so each data source has its own.
internal fun pendingFile(sessionFile: File): File = File(sessionFile.absoluteFile.parentFile, sessionFile.name + ".sign-in")

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
    private val exchangeWithin: Duration = EXCHANGE_WITHIN,
    private val random: SecureRandom = SecureRandom(),
) : SignIn {
    private val state = MutableStateFlow<SignInStep>(SignInStep.None)
    override val step = state.asStateFlow()
    override val canStart: Boolean get() = clientId.isNotBlank()

    // One start or redirect at a time, in the order they came.
    private val turns = Mutex()

    // Guards the four values below; [step] is made from them and from nothing else.
    private val lock = Any()

    // Redirects that arrived and are not dealt with yet: while there is one, the sign-in is being completed.
    private var open = 0

    // How the last sign-in ended, until it is acknowledged.
    private var end: SignInStep = SignInStep.None
    private var arrivals = 0L

    // Redirects up to this one came before a sign-in that was taken had ended, and say nothing after it.
    private var settled = 0L

    override suspend fun start(browser: SignInBrowser) {
        synchronized(lock) {
            end = SignInStep.None
            shown()
        }
        if (!canStart) return failed(SignInFailure.Unavailable)
        val address = scope.async { turns.withLock { guarded(null) { begun() } } }.await() ?: return failed(SignInFailure.NotStored)
        if (!guarded(false) { browser.open(address) }) failed(SignInFailure.NoBrowser)
    }

    // Counted in and, however its handling ends, counted out again: the step cannot stay at being completed.
    override fun redirected(uri: String) {
        val arrival =
            synchronized(lock) {
                open++
                shown()
                ++arrivals
            }
        var dealt: Dealt? = null
        scope.launch { turns.withLock { dealt = taken(uri) } }.invokeOnCompletion { closed(arrival, dealt) }
    }

    override fun acknowledge(step: SignInStep) {
        synchronized(lock) {
            if ((step is SignInStep.Failed || step == SignInStep.SignedIn) && end == step) end = SignInStep.None
            shown()
        }
    }

    private fun shown() {
        state.value = if (open > 0 && end != SignInStep.SignedIn) SignInStep.Completing else end
    }

    // A redirect refused after a sign-in was taken says nothing if it came before that sign-in ended, and never hides a success.
    private fun closed(
        arrival: Long,
        dealt: Dealt?,
    ) {
        val reported =
            synchronized(lock) {
                open--
                val said = dealt != null && (dealt.taken || arrival > settled)
                if (dealt != null && dealt.taken) settled = arrivals
                if (dealt != null && said && (dealt.taken || end != SignInStep.SignedIn)) end = dealt.end
                shown()
                (dealt?.end as? SignInStep.Failed)?.reason?.takeIf { said }
            }
        reported?.let { onFailure?.invoke(it) }
    }

    private fun failed(reason: SignInFailure) {
        synchronized(lock) {
            if (end != SignInStep.SignedIn) end = SignInStep.Failed(reason)
            shown()
        }
        onFailure?.invoke(reason)
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

    private suspend fun taken(uri: String): Dealt =
        when (val redirect = guarded(Refused(SignInFailure.NotStored)) { checked(uri) }) {
            is Refused -> Dealt(SignInStep.Failed(redirect.reason), taken = false)
            is Accepted -> {
                val reason = guarded(SignInFailure.NoTokens) { exchanged(redirect) }
                Dealt(if (reason == null) SignInStep.SignedIn else SignInStep.Failed(reason), taken = true)
            }
        }

    // S3: the answer to the sign-in that waits, with its state, started a short time ago, and taken once.
    private suspend fun checked(uri: String): Redirect {
        val values = redirectValues(uri, redirectUri) ?: return Refused(SignInFailure.Unreadable)
        val pending = store.read() ?: return Refused(SignInFailure.Unasked)
        // Only a redirect with the state ends the sign-in that waits, an old one too.
        if (!same(values["state"], pending.state)) return Refused(SignInFailure.WrongState)
        if (!store.write(null)) return Refused(SignInFailure.NotStored)
        // A clock set back since the start by less than the limit does not make the sign-in old.
        if (abs(now() - pending.startedAt) > STARTED_WITHIN.seconds) return Refused(SignInFailure.Old)
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
        val reply = withTimeoutOrNull(exchangeWithin.toMillis()) { endpoint.post(grant) } ?: return SignInFailure.NoTokens
        return when (val result = reply.read()) {
            is TokenResult.Granted ->
                when (signIn(result.grant)) {
                    is Outcome.Success -> null
                    is Outcome.Failure -> SignInFailure.NotStored
                }
            TokenResult.Rejected -> SignInFailure.Rejected
            is TokenResult.Failed -> SignInFailure.NoTokens
        }
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

    // How a redirect was dealt with: the end it leads to, and whether it was the answer to the sign-in that waited.
    private class Dealt(
        val end: SignInStep,
        val taken: Boolean,
    )

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
