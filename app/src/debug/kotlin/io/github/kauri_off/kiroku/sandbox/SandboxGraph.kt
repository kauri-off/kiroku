package io.github.kauri_off.kiroku.sandbox

import android.content.Context
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.repository.SignIn
import io.github.kauri_off.kiroku.core.repository.SignInBrowser
import io.github.kauri_off.kiroku.data.network.AndroidDevice
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.sandbox.Sandbox
import io.github.kauri_off.kiroku.data.sandbox.SandboxMal
import io.github.kauri_off.kiroku.data.sandbox.SandboxWrites
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.scenario.ScenarioSubject
import io.github.kauri_off.kiroku.scenario.Scenarios
import io.github.kauri_off.kiroku.scenario.ScenariosOwner
import io.github.kauri_off.kiroku.scenario.WriteFault
import io.github.kauri_off.kiroku.signin.SignInOwner
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.LiveGraph
import io.github.kauri_off.kiroku.wiring.LiveParts
import io.github.kauri_off.kiroku.wiring.LiveSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock

// The live graph whole over MAL on the device, which keeps a made-up list in the source's own files.
class SandboxGraph internal constructor(
    private val parts: LiveParts,
    val sandbox: Sandbox,
    private val device: SandboxDevice,
    private val live: LiveGraph = LiveGraph(parts),
) : AppGraph by live,
    SignInOwner,
    ScenariosOwner {
    override val signIn: SignIn by lazy { SandboxSignIn(live.signIn, sandbox.mal) }

    override val scenarios = Scenarios(SandboxScenarios(), parts.scope)

    override val madeUp = true

    suspend fun writeOwed() = live.writeOwed()

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
        }

        override fun expireAccess() = this@SandboxGraph.sandbox.mal.expireAccess()

        override val saving = false

        override suspend fun awaitSaves() = true

        // The sandbox's list as it started, which the next sync brings to the app.
        override suspend fun reset(): Outcome<Unit> {
            withContext(Dispatchers.IO) { this@SandboxGraph.sandbox.reset() }
            return list.sync()
        }
    }

    companion object {
        // The sandbox of this app's files; nothing is read or built before its first request.
        internal fun create(
            context: Context,
            clock: Clock,
            device: Device = AndroidDevice(context),
            parts: (LiveSource, Device) -> LiveParts,
        ): SandboxGraph {
            // The titles of the public answers the debug and preview builds hold as assets; posters are the stand-in art's.
            val sandbox =
                Sandbox(
                    read = { name -> context.assets.open(notShown("recorded/$name.200.json")).use { it.readBytes().decodeToString() } },
                    poster = { id -> notShown("${FixtureAnime.POSTER_URL}$id.jpg") },
                    clock = clock,
                    file = { kind -> File(context.noBackupFilesDir, notShown("$NAME.mal.$kind")) },
                )
            val sandboxDevice = SandboxDevice(device)
            return SandboxGraph(parts(LiveSource(NAME, SandboxMal.Hosts, sandbox.mal), sandboxDevice), sandbox, sandboxDevice)
        }

        private val NAME = notShown("sandbox")
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
