package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.TestClock
import io.github.kauri_off.kiroku.data.network.scratchBackOffFile
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.MalTokenEndpoint
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

// The app's HTTP client with the sandbox as its last step, on a device that is offline while the sandbox is.
internal class SandboxClient(
    val mal: SandboxMal,
    io: CoroutineDispatcher = Dispatchers.IO,
    // Calls run on the thread that makes them, so a test's scheduler sees each answer at once.
    inline: Boolean = false,
    val clock: TestClock = TestClock(),
) {
    val unexpected = CopyOnWriteArrayList<Throwable>()
    val log = CopyOnWriteArrayList<String>()

    val http =
        MalHttp(
            hosts = SandboxMal.Hosts,
            clientId = SandboxMal.CLIENT_ID,
            appVersion = "1.2.3",
            device =
                object : Device {
                    override fun isOnline() = !mal.offline

                    override fun observeOnline() = flowOf(!mal.offline)

                    override fun isMainThread() = false
                },
            clock = clock,
            backOffFile = scratchBackOffFile(),
            unexpected = unexpected::add,
            log = log::add,
            repeatReadsAfter = listOf(1.milliseconds, 1.milliseconds),
            io = io,
            standIn = mal,
            calls = if (inline) Dispatcher(OnTheCallersThread()) else null,
        )

    class SignedIn(
        val sessions: SessionFixture,
        val session: MalSession,
        val access: String,
        val refresh: String,
    )

    // A session over a file in [folder] whose refreshes go to the sandbox, signed in with tokens the sandbox granted.
    fun signedIn(
        folder: File,
        tokens: SandboxTokens,
    ): SignedIn {
        val sessions = SessionFixture(folder)
        sessions.over(MalTokenEndpoint(http))
        val session = sessions.open()
        val (access, refresh) = tokens.granted()
        runBlocking { session.signIn(TokenGrant(access, refresh)) }
        return SignedIn(sessions, session, access, refresh)
    }
}

private class OnTheCallersThread : AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()

    override fun shutdown() = Unit

    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

    override fun isShutdown() = false

    override fun isTerminated() = false

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ) = true
}
