package io.github.kauri_off.kiroku.sandbox

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.BrowseSession
import io.github.kauri_off.kiroku.core.repository.MangaCatalogue
import io.github.kauri_off.kiroku.core.repository.MangaListReader
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.Ranked
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.SignIn
import io.github.kauri_off.kiroku.core.repository.SignInBrowser
import io.github.kauri_off.kiroku.data.network.AndroidDevice
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.sandbox.Planted
import io.github.kauri_off.kiroku.data.sandbox.Sandbox
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring
import io.github.kauri_off.kiroku.data.sandbox.SandboxAniListFault
import io.github.kauri_off.kiroku.data.sandbox.SandboxMal
import io.github.kauri_off.kiroku.data.sandbox.SandboxManga
import io.github.kauri_off.kiroku.data.sandbox.SandboxWrites
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.notificationlog.RunLog
import io.github.kauri_off.kiroku.scenario.AniListFault
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.scenario.ScenarioSubject
import io.github.kauri_off.kiroku.scenario.Scenarios
import io.github.kauri_off.kiroku.scenario.ScenariosOwner
import io.github.kauri_off.kiroku.scenario.WriteFault
import io.github.kauri_off.kiroku.search.SearchFixtureStates
import io.github.kauri_off.kiroku.signin.SignInOwner
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.DeviceTime
import io.github.kauri_off.kiroku.wiring.LiveGraph
import io.github.kauri_off.kiroku.wiring.LiveParts
import io.github.kauri_off.kiroku.wiring.LiveSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Duration
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

// The live graph whole over MAL on the device, which keeps a made-up list in the source's own files.
class SandboxGraph internal constructor(
    private val parts: LiveParts,
    val sandbox: Sandbox,
    private val device: SandboxDevice,
    private val aniListTime: SandboxAniListTime,
    private val live: LiveGraph = LiveGraph(parts),
) : AppGraph by live,
    SignInOwner,
    ScenariosOwner {
    override val signIn: SignIn by lazy { SandboxSignIn(live.signIn, sandbox.mal) }

    override val scenarios = Scenarios(SandboxScenarios(), parts.scope)

    override val madeUp = true

    override val mangaCatalogue: MangaCatalogue? get() = null

    private val mangaFixtures by lazy {
        SandboxManga(
            { parts.database },
            live.account,
            clock,
            device,
            sandbox.mal,
            poster = { id -> notShown("${FixtureAnime.POSTER_URL}$id.jpg") },
            unexpected = parts.unexpected,
            scope = parts.scope,
        )
    }

    override val manga: MangaRepository get() = mangaFixtures

    override val mangaList: MangaListReader get() = mangaFixtures

    override val session: SessionRepository by lazy {
        object : SessionRepository by live.session {
            override suspend fun signOut() {
                live.account.currentSession()?.let { this@SandboxGraph.signOut(it) }
            }
        }
    }

    override suspend fun signOut(expected: Session) {
        live.signOut(expected)
        live.account.currentSession()?.let { mangaFixtures.forgetRetired(it.owner) }
    }

    override val search: SearchRepository by lazy {
        val fixtures = SearchFixtureStates.standIn(Fixtures.at(clock))
        object : SearchRepository by live.search {
            override suspend fun search(
                query: String,
                offset: Int,
                includeAdult: Boolean,
            ) = Outcome.Success(Page(fixtures.search(query).drop(offset).map { it.anime }, nextOffset = null))

            override fun openSuggestions(owner: SignInId): BrowseSession = openBrowse(BrowseList.Suggestions)

            override fun openBrowse(list: BrowseList): BrowseSession =
                BrowseSession { offset, _ ->
                    Outcome.Success(Page(fixtures.browse(list).drop(offset).map { Ranked(it.rank, it.anime) }, nextOffset = null))
                }
        }
    }

    suspend fun writeOwed() = live.writeOwed()

    // What the notification engine did, kept in the sandbox's files.
    val runLog: RunLog get() = live.runLog

    // An episode of the planted title airs [after] from now at AniList; the app asks for it at once, as a refresh would.
    suspend fun plant(after: Duration): Planted? =
        withContext(Dispatchers.IO) { sandbox.plant(clock.instant().plus(after)) }?.also {
            parts.scope.launch { airing.refresh(setOf(AnimeId(SandboxAiring.SOON))) }
        }

    // AniList moves the planted episode [by] later and the app is not told: it learns of it when it next asks, as from AniList.
    suspend fun postpone(by: Duration): Planted? = withContext(Dispatchers.IO) { sandbox.postpone(by) }

    private inner class SandboxScenarios : ScenarioSubject {
        override val sandbox = true

        override fun apply(scenario: Scenario) {
            val mal = this@SandboxGraph.sandbox.mal
            mal.offline = scenario.offline
            device.offline.value = scenario.offline
            mal.rejecting = scenario.sessionExpired
            mal.delayMillis = if (scenario.slow) Scenarios.SLOW.inWholeMilliseconds else 0
            mal.writes =
                when (scenario.writes) {
                    WriteFault.None -> SandboxWrites.Work
                    WriteFault.Refused -> SandboxWrites.Refused
                    WriteFault.ServerError -> SandboxWrites.Fail
                }
            this@SandboxGraph.sandbox.aniList.fault =
                when (scenario.aniList) {
                    AniListFault.None -> SandboxAniListFault.None
                    AniListFault.Fails -> SandboxAniListFault.Fails
                    AniListFault.NoAnswer -> SandboxAniListFault.NoAnswer
                }
        }

        override fun expireAccess() = this@SandboxGraph.sandbox.mal.expireAccess()

        override val saving = false

        override suspend fun awaitSaves() = true

        // The sandbox's list as it started, which the next sync brings to the app, and its schedules, which a refresh brings.
        // They are laid out before AniList answers again, so that a request the reset releases sees them.
        override suspend fun reset(off: () -> Unit): Outcome<Unit> {
            withContext(Dispatchers.IO) { this@SandboxGraph.sandbox.reset() }
            aniListTime.passHolds()
            off()
            return list.sync().also { parts.scope.launch { airing.refresh() } }
        }
    }

    companion object {
        // The sandbox of this app's files; nothing is read or built before its first request.
        internal fun create(
            context: Context,
            deviceClock: Clock,
            device: Device = AndroidDevice(context),
            parts: (LiveSource, Device, Clock) -> LiveParts,
        ): SandboxGraph {
            val clock = movedClock ?: deviceClock
            // The titles of the public answers the debug and preview builds hold as assets; posters are the stand-in art's.
            val sandbox =
                Sandbox(
                    read = { name -> context.assets.open(notShown("recorded/$name.200.json")).use { it.readBytes().decodeToString() } },
                    readTenrai = { file -> context.assets.open(notShown("tenrai/$file")).use { it.readBytes().decodeToString() } },
                    poster = { id -> notShown("${FixtureAnime.POSTER_URL}$id.jpg") },
                    clock = clock,
                    file = { kind -> File(context.noBackupFilesDir, notShown("$NAME.mal.$kind")) },
                    online = device::isOnline,
                )
            val sandboxDevice = SandboxDevice(device)
            val time = SandboxAniListTime(aniListTime)
            val source = LiveSource(NAME, SandboxMal.Hosts, sandbox.mal, aniListTime = time)
            return SandboxGraph(parts(source, sandboxDevice, clock), sandbox, sandboxDevice, time)
        }

        private val NAME = notShown("sandbox")

        // What the sandbox's AniList client spaces its requests by: the device's time, which a test may hurry.
        @VisibleForTesting
        @Volatile
        internal var aniListTime: TimeSource.WithComparableMarks = DeviceTime

        // The clock of the sandbox and of the app on it, which a test may move on; the device's when null.
        @VisibleForTesting
        @Volatile
        internal var movedClock: Clock? = null
    }
}

// [base], a day on at each [passHolds]: past every cool-down and wait the AniList client keeps after a failure.
internal class SandboxAniListTime(
    base: TimeSource.WithComparableMarks,
) : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    private val start = base.markNow()

    @Volatile
    private var passed = 0L

    override fun read(): Long = start.elapsedNow().inWholeNanoseconds + passed

    fun passHolds() {
        passed += Holds.inWholeNanoseconds
    }

    private companion object {
        val Holds = 1.days
    }
}

// The device, offline also while the panel says so, as the sandbox then answers nothing.
class SandboxDevice(
    private val device: Device,
) : Device {
    val offline = MutableStateFlow(false)

    override fun isOnline(): Boolean = !offline.value && device.isOnline()

    override fun observeOnline(): Flow<Boolean> = combine(device.observeOnline(), offline) { online, off -> online && !off }

    override fun isMainThread(): Boolean = device.isMainThread()
}

// The sign-in with MAL's page allowed at once: the sandbox's redirect comes back as the browser would bring it.
private class SandboxSignIn(
    private val signIn: SignIn,
    private val mal: SandboxMal,
) : SignIn by signIn {
    override suspend fun start(browser: SignInBrowser) =
        signIn.start { address -> mal.authorize(address)?.also(signIn::redirected) != null }
}
