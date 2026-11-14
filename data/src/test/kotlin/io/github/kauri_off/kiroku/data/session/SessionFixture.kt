package io.github.kauri_off.kiroku.data.session

import androidx.datastore.core.Serializer
import io.github.kauri_off.kiroku.core.error.Outcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import java.io.File
import java.security.ProviderException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.CoroutineContext

internal const val SIGN_IN = "0f8fad5b-d9cb-469f-a165-70867728950e"
internal const val OTHER_SIGN_IN = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

internal fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

// Keys as a test holds them: [key] may be absent or useless, and every key made is counted.
internal class TestKeys(
    var key: SecretKey? = aesKey(),
) : KeySource {
    var made = 0

    override fun existing(): SecretKey? = key

    override fun fresh(): SecretKey =
        aesKey().also {
            key = it
            made++
        }
}

internal fun resource(name: String): String = checkNotNull(SessionFixture::class.java.getResource("/$name")) { name }.readText()

// MAL's printed token answer, with other tokens in the places of its two.
internal fun tokenAnswer(
    access: String,
    refresh: String,
): String = resource("mal/authorization/token.json").replace("ACCESS_TOKEN", access).replace("REFRESH_TOKEN", refresh)

internal fun granted(
    access: String,
    refresh: String,
): TokenReply = TokenReply.Answer(200, tokenAnswer(access, refresh))

internal fun Stored.text(): String =
    when (this) {
        Stored.SignedOut -> "signed out"
        is Stored.Expired -> "expired $signIn"
        is Stored.SignedIn -> "${tokens.signIn} ${tokens.access} ${tokens.refresh} ${tokens.obtainedAt}"
    }

internal class FailingCipher(
    private val real: TokenCipher,
    var opens: Boolean = true,
    var seals: Boolean = true,
) : TokenCipher {
    override fun seal(
        plain: ByteArray,
        bound: ByteArray,
    ): ByteArray = if (seals) real.seal(plain, bound) else throw ProviderException("no Keystore")

    override fun open(
        sealed: ByteArray,
        bound: ByteArray,
    ): ByteArray = if (opens) real.open(sealed, bound) else throw ProviderException("no Keystore")
}

internal class TestClock(
    var now: Instant = Instant.parse("2026-11-13T10:00:00Z"),
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advance(by: Duration) {
        now += by
    }
}

// A MAL that accepts only [valid] access tokens and remembers every token it was sent.
internal class FakeMal(
    vararg valid: String,
) {
    val valid = CopyOnWriteArrayList(valid.toList())
    val sent = CopyOnWriteArrayList<String>()

    val request =
        AuthorisedRequest { token ->
            sent += token
            if (token in this.valid) Attempt.Answered(Outcome.Success(token)) else Attempt.Rejected
        }
}

// Sessions over one file, one at a time, as successive processes would hold it.
internal class SessionFixture(
    directory: File,
) {
    val file = File(directory, "session")
    val keys = TestKeys()
    val cipher: TokenCipher = AesGcmCipher(keys)
    val clock = TestClock()
    val unexpected = CopyOnWriteArrayList<Throwable>()

    // What was reported, as the reporter may print it: types only.
    val reported: List<String> get() = unexpected.map { it.message.orEmpty() }
    val grants = CopyOnWriteArrayList<Map<String, String>>()
    var answer: suspend (Map<String, String>) -> TokenReply = { error("No token request was expected") }
    private val scopes = mutableListOf<CoroutineScope>()

    // The scope of the session opened last. A plain job: an exception that leaves a coroutine of the session ends it.
    lateinit var sessionScope: CoroutineScope
        private set

    private fun scope(context: CoroutineContext): CoroutineScope = CoroutineScope(context).also { scopes += it }

    fun format(
        cipher: TokenCipher = this.cipher,
        name: String = file.name,
    ) = TokenFormat(cipher, name) { unexpected += it }

    fun store(format: Serializer<Stored> = format()) = TokenStore(file, format, scope(SupervisorJob() + Dispatchers.IO))

    fun open(
        cipher: TokenCipher = this.cipher,
        format: Serializer<Stored> = format(cipher),
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        retryAfter: (Int) -> Duration = ::fileRetry,
    ): MalSession {
        sessionScope = scope(Job() + dispatcher)
        val endpoint =
            TokenEndpoint { grant ->
                grants += grant
                answer(grant)
            }
        return MalSession(store(format), endpoint, clock, sessionScope, { unexpected += it }, retryAfter)
    }

    suspend fun close() {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        scopes.clear()
    }

    suspend fun reopen(cipher: TokenCipher = this.cipher): MalSession {
        close()
        return open(cipher)
    }
}
