package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureSet
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListEngine
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// [unexpected] does not hear of the faults the demo's store was told to make.
class Demo(
    val fixtures: FixtureSet,
    scope: CoroutineScope,
    private val latency: Duration = Duration.ZERO,
    val clock: Clock = fixtures.clock,
    unexpected: (Throwable) -> Unit = { throw it },
) {
    val server = DemoServer(fixtures.entries, clock, latency)

    val store = DemoStore(fixtures.entries)

    private val signedIn = MutableStateFlow(true)

    val session: SessionRepository = DemoSessionRepository(signedIn, server, fixtures.user.name)

    val list: ListRepository = ListEngine(server, store, signedIn, clock, scope) { if (it !is DemoStoreFault) unexpected(it) }

    private val details = DemoAnimeRepository(fixtures, server)

    val anime: AnimeRepository = details

    val airing: AiringRepository = DemoAiringRepository(fixtures, server)

    val search: SearchRepository = DemoSearchRepository(fixtures, server)

    val user: UserRepository = DemoUserRepository(fixtures, server, signedIn)

    // The demo as it started: every failure off, signed in, no details fetched, and the list synced back to the fixtures.
    suspend fun reset(): Outcome<Unit> {
        server.reset(fixtures.entries, latency)
        store.reset()
        details.forget()
        signedIn.value = true
        return list.sync()
    }

    companion object {
        val latency: Duration = 400.milliseconds

        fun startingAt(
            clock: Clock,
            scope: CoroutineScope,
            unexpected: (Throwable) -> Unit = { throw it },
        ): Demo = Demo(Fixtures.at(clock), scope, latency, unexpected = unexpected)
    }
}
