package io.github.kauri_off.kiroku.data.session

import androidx.datastore.core.Serializer
import io.github.kauri_off.kiroku.core.error.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.plus
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

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
        Stored.Expired -> "expired"
        is Stored.SignedIn -> "${tokens.signIn} ${tokens.access} ${tokens.refresh} ${tokens.obtainedAt}"
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
    val key = aesKey()
    val clock = TestClock()
    val unexpected = CopyOnWriteArrayList<Throwable>()
    val grants = CopyOnWriteArrayList<Map<String, String>>()
    var answer: suspend (Map<String, String>) -> TokenReply = { error("No token request was expected") }
    private val scopes = mutableListOf<CoroutineScope>()

    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    fun store(format: Serializer<Stored> = TokenFormat(AesGcmCipher { key })) = TokenStore(file, format, scope() + Dispatchers.IO)

    fun open(cipher: TokenCipher = AesGcmCipher { key }): MalSession {
        val scope = scope()
        val endpoint =
            TokenEndpoint { grant ->
                grants += grant
                answer(grant)
            }
        return MalSession(TokenStore(file, TokenFormat(cipher), scope + Dispatchers.IO), endpoint, clock, scope) { unexpected += it }
    }

    suspend fun close() {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        scopes.clear()
    }

    suspend fun reopen(cipher: TokenCipher = AesGcmCipher { key }): MalSession {
        close()
        return open(cipher)
    }
}
