package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureSet
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListEngine
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// [unexpected] does not hear of the faults the demo's store was told to make.
class Demo(
    val fixtures: FixtureSet,
    scope: CoroutineScope,
    private val latency: Duration = Duration.ZERO,
    val clock: Clock = fixtures.clock,
    unexpected: (Throwable) -> Unit = { throw it },
) {
    val server = DemoServer(fixtures.entries, clock, latency)

    val store = DemoStore(fixtures.entries, fixtures.session.signIn)

    // Null while signed out; a sign-in after a sign-out is a new one, with its own list.
    private val signIn = MutableStateFlow<SignInId?>(fixtures.session.signIn)
    private var signIns = 1

    val session: SessionRepository = DemoSessionRepository(signIn, server)

    val list: ListRepository =
        ListEngine(server, store, session.observeSession(), clock, scope) { if (it !is DemoStoreFault) unexpected(it) }

    private val details = DemoAnimeRepository(fixtures, server)

    val anime: AnimeRepository = details

    val airing: AiringRepository = DemoAiringRepository(fixtures, server)

    val search: SearchRepository = DemoSearchRepository(fixtures, server)

    val user: UserRepository = DemoUserRepository(fixtures, server, signIn)

    // Whether an edit is on its way to MAL.
    val saving: Boolean get() = server.writing.value > 0 || store.unconfirmed.value

    // Waits for every edit on its way to MAL to be answered; false when one stays unconfirmed with nothing being sent.
    suspend fun awaitSaves(): Boolean {
        while (true) {
            server.writing.first { it == 0 }
            if (!store.unconfirmed.value) return true
            withTimeoutOrNull(QUIET) {
                combine(server.writing, store.unconfirmed) { writing, unconfirmed -> writing > 0 || !unconfirmed }.first { it }
            } ?: return false
        }
    }

    // The demo as it started: every failure off, signed in, no details fetched, and the list synced back to the fixtures.
    suspend fun reset(): Outcome<Unit> {
        server.reset(fixtures.entries, latency)
        store.reset()
        details.forget()
        if (signIn.value == null) signIn.value = SignInId("demo-${++signIns}")
        return list.sync()
    }

    companion object {
        val latency: Duration = 400.milliseconds

        // Longer than the engine ever takes between one answer and its next request.
        private val QUIET = 1.seconds

        fun startingAt(
            clock: Clock,
            scope: CoroutineScope,
            unexpected: (Throwable) -> Unit = { throw it },
        ): Demo = Demo(Fixtures.at(clock), scope, latency, unexpected = unexpected)
    }
}
