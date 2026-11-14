package io.github.kauri_off.kiroku.data.signin

import io.github.kauri_off.kiroku.core.repository.SignInBrowser
import io.github.kauri_off.kiroku.core.repository.SignInFailure
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenCipher
import io.github.kauri_off.kiroku.data.session.TokenEndpoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

internal const val REDIRECT = "io.github.kauri-off.kiroku.debug://oauth"
internal const val AUTHORIZE = "https://mal.test/v1/oauth2/authorize"
internal const val CLIENT = "the-client-id-0f3a"
internal const val CODE = "the-code-51d7"

internal class FakeBrowser(
    var opens: Boolean = true,
) : SignInBrowser {
    val opened = CopyOnWriteArrayList<String>()

    override fun open(address: String): Boolean {
        opened += address
        return opens
    }
}

// A value of the authorisation page's address.
internal fun String.asked(name: String): String = checkNotNull(toHttpUrl().queryParameter(name)) { name }

// The redirect MAL sends for the sign-in whose page is at [address].
internal fun redirectOf(
    address: String,
    code: String = CODE,
): String = "$REDIRECT?code=$code&state=${address.asked("state")}"

// The step once the redirect in hand was dealt with.
internal suspend fun MalSignIn.ended(): SignInStep = withTimeout(10_000) { step.first { it != SignInStep.Completing } }

// The sign-in and its session over one directory, one pair at a time, as successive processes would hold them.
internal class SignInFixture(
    directory: File,
) {
    val sessions = SessionFixture(directory)
    val file = File(directory, "sign-in")
    val browser = FakeBrowser()
    val failures = CopyOnWriteArrayList<SignInFailure>()
    var clientId = CLIENT
    lateinit var session: MalSession
        private set
    private var scope: CoroutineScope? = null

    // A plain job: an exception that leaves a coroutine of the sign-in ends it.
    fun open(
        cipher: TokenCipher = sessions.cipher,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): MalSignIn {
        session = sessions.open(cipher)
        val scope = CoroutineScope(Job() + dispatcher).also { this.scope = it }
        val endpoint =
            TokenEndpoint { grant ->
                sessions.grants += grant
                sessions.answer(grant)
            }
        return MalSignIn(
            authorize = AUTHORIZE,
            clientId = clientId,
            redirectUri = REDIRECT,
            store = PendingStore(file, scope + Dispatchers.IO),
            endpoint = endpoint,
            signIn = session::signIn,
            clock = sessions.clock,
            scope = scope,
            unexpected = { sessions.unexpected += it },
            onFailure = { failures += it },
        )
    }

    suspend fun close() {
        scope?.coroutineContext?.job?.cancelAndJoin()
        scope = null
        sessions.close()
    }

    suspend fun reopen(): MalSignIn {
        close()
        return open()
    }

    // The address of the page a new sign-in opened.
    suspend fun started(signIn: MalSignIn): String {
        signIn.start(browser)
        return browser.opened.last()
    }
}
