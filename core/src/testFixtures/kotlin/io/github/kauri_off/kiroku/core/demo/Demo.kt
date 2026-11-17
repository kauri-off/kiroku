package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.airing.AiringTime
import io.github.kauri_off.kiroku.core.airing.InMemoryAiringStore
import io.github.kauri_off.kiroku.core.airing.ListAiring
import io.github.kauri_off.kiroku.core.airing.StoredAiring
import io.github.kauri_off.kiroku.core.airing.tracked
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureSet
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListEngine
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.notify.InMemoryNotificationAlarm
import io.github.kauri_off.kiroku.core.notify.InMemoryNotificationStore
import io.github.kauri_off.kiroku.core.notify.InMemoryShownNotifications
import io.github.kauri_off.kiroku.core.notify.NotificationAlarm
import io.github.kauri_off.kiroku.core.notify.NotificationEngine
import io.github.kauri_off.kiroku.core.notify.RunReport
import io.github.kauri_off.kiroku.core.notify.ShownNotifications
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
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
    // Whether [clock] runs with the dispatcher's time, so that airing can wait for an air time; if not, [clockSet] moves it.
    clockRuns: Boolean = false,
    clockSet: Flow<Unit> = emptyFlow(),
    // Whether the airing store starts as fetched just now; if not, it starts empty, as after an install.
    airingStored: Boolean = true,
    // The alarm and the notifications of the demo's device; in memory when none is given.
    alarm: NotificationAlarm = InMemoryNotificationAlarm(),
    shown: ShownNotifications = InMemoryShownNotifications(),
    runs: (RunReport) -> Unit = {},
) {
    val server = DemoServer(fixtures.entries, clock, latency)

    val store = DemoStore(fixtures.entries, fixtures.session.signIn)

    // Null while signed out; a sign-in after a sign-out is a new one, with its own list.
    private val signIn = MutableStateFlow<SignInId?>(fixtures.session.signIn)
    private var signIns = 1

    val session: SessionRepository = DemoSessionRepository(signIn, server)

    val list: ListRepository =
        ListEngine(server, store, session.observeSession(), clock, scope) { if (it !is DemoStoreFault) unexpected(it) }

    private val details = DemoAnimeRepository(fixtures, server, clock)

    val anime: AnimeRepository = details

    val connection: Connection = DemoConnection(server)

    val airingSource = DemoAiringSource(fixtures, server)

    // As fetched just now, as a device that was just synced, or empty.
    private val startingAiring = {
        fixtures.entries.filter { airingStored && it.tracked }.associate {
            it.anime.id to StoredAiring(fixtures.schedules[it.anime.id], clock.instant())
        }
    }

    private val airingStore = InMemoryAiringStore(startingAiring())

    // On the real rules over a store in memory.
    val airing: AiringRepository =
        ListAiring(
            airingSource,
            airingStore,
            list,
            AiringTime(clock, clockSet) { if (clockRuns) delay(it) else awaitCancellation() },
            scope,
            unexpected,
        )

    // On the real rules over a record in memory; it follows the list from its first use, as the live one does.
    val notifications: NotificationEngine by lazy {
        NotificationEngine(
            InMemoryNotificationStore(),
            alarm,
            shown,
            list,
            airing,
            session.observeSession(),
            connection,
            clock,
            scope,
            reports = runs,
            unexpected = unexpected,
        )
    }

    val search: SearchRepository = DemoSearchRepository(fixtures, server)

    val user: UserRepository = DemoUserRepository(fixtures, server, signIn)

    val preferences: PreferencesRepository = DemoPreferences()

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

    // The demo as it started: every failure off, signed in, no details fetched, the list and the airing data back to the fixtures.
    suspend fun reset(): Outcome<Unit> {
        server.reset(fixtures.entries, latency)
        airingSource.failure = null
        airingSource.schedules = fixtures.schedules
        airingSource.hold(false)
        airingStore.write(airingStore.read().mapValues { null } + startingAiring())
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
            clockSet: Flow<Unit> = emptyFlow(),
            alarm: NotificationAlarm = InMemoryNotificationAlarm(),
            shown: ShownNotifications = InMemoryShownNotifications(),
            runs: (RunReport) -> Unit = {},
        ): Demo =
            Demo(
                Fixtures.at(clock),
                scope,
                latency,
                unexpected = unexpected,
                clockRuns = true,
                clockSet = clockSet,
                alarm = alarm,
                shown = shown,
                runs = runs,
            )
    }
}
