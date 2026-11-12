package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.fixtures.FixtureSet
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
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

class Demo(
    val fixtures: FixtureSet,
    scope: CoroutineScope,
    latency: Duration = Duration.ZERO,
    val clock: Clock = fixtures.clock,
) {
    val server = DemoServer(fixtures.entries, clock, latency)

    private val signedIn = MutableStateFlow(true)

    val session: SessionRepository = DemoSessionRepository(signedIn, server, fixtures.user.name)

    val list: ListRepository = ListEngine(server, InMemoryLocalList(fixtures.entries), signedIn, clock, scope)

    val anime: AnimeRepository = DemoAnimeRepository(fixtures, server)

    val airing: AiringRepository = DemoAiringRepository(fixtures, server)

    val search: SearchRepository = DemoSearchRepository(fixtures, server)

    val user: UserRepository = DemoUserRepository(fixtures, server, signedIn)

    companion object {
        val latency: Duration = 400.milliseconds

        fun startingAt(
            clock: Clock,
            scope: CoroutineScope,
        ): Demo = Demo(Fixtures.at(clock), scope, latency)
    }
}
