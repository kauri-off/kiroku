package io.github.kauri_off.kiroku.debugmenu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.data.recorder.Halt
import io.github.kauri_off.kiroku.data.recorder.ListAfter
import io.github.kauri_off.kiroku.data.recorder.RecordedTitle
import io.github.kauri_off.kiroku.data.recorder.Recording
import io.github.kauri_off.kiroku.list.FixtureListScreen
import io.github.kauri_off.kiroku.list.ListFixtureStates
import io.github.kauri_off.kiroku.recorder.RecorderSection
import io.github.kauri_off.kiroku.recorder.RecorderState
import io.github.kauri_off.kiroku.scenario.EdgeMarker
import io.github.kauri_off.kiroku.scenario.ResetState
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.scenario.ScenarioSection
import io.github.kauri_off.kiroku.scenario.ScenarioState
import io.github.kauri_off.kiroku.scenario.StoreFault
import io.github.kauri_off.kiroku.scenario.WriteFault
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.signout.SignOutSection
import io.github.kauri_off.kiroku.signout.SignOutState
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.SourceSection
import io.github.kauri_off.kiroku.source.SourceState
import java.io.File
import java.time.Instant

class DebugMenuScreenshots : ScreenshotRegistry() {
    private val several = Scenario(offline = true, slow = true, writes = WriteFault.Refused, store = StoreFault.WritesFail)

    override val content = contentCase { Menu(ScenarioState.Ready(Scenario())) }
    override val loading = notShown("The menu reads its switches from memory and its build from the package, and never waits")
    override val empty = notShown("The menu always has its build and the gallery; off the demo its scenarios say why there are none")
    override val error = notShown("The menu makes no request that can fail")

    override val cases =
        listOf(
            case("several-on") { Menu(ScenarioState.Ready(several)) },
            case("waiting-for-save") { Menu(ScenarioState.Ready(several, ResetState.WaitingForSave)) },
            case("save-dropped") { Menu(ScenarioState.Ready(Scenario(), ResetState.SaveDropped)) },
            case("real-account") { Menu(ScenarioState.Unavailable) },
            case("sandbox") {
                Menu(
                    ScenarioState.Ready(several.copy(store = StoreFault.None), sandbox = true),
                    source = SourceState(DataSource.Sandbox, DataSource.Sandbox),
                    signOut = SignOutState.Account(Session.SignedIn(SignInId(SIGN_IN))),
                    recorder = RecorderState(null),
                )
            },
            case("signed-out") { Menu(ScenarioState.Unavailable, signOut = SignOutState.Account(Session.SignedOut)) },
            case("source-chosen") { Menu(ScenarioState.Unavailable, source = SourceState(DataSource.Account, chosen = DataSource.Demo)) },
            case(
                "source-not-kept",
            ) { Menu(ScenarioState.Ready(Scenario()), source = SourceState(DataSource.Demo, DataSource.Demo, kept = false)) },
            case("uncommitted-build") { Menu(ScenarioState.Unavailable, BUILD.copy(commit = Commit(HASH, dirty = true))) },
            case("unnamed-build") { Menu(ScenarioState.Unavailable, BUILD.copy(commit = null, built = null)) },
            case("recorder-confirm") { Menu(ScenarioState.Unavailable, recorder = RecorderState(Recording.Checked(TITLE), "1")) },
            case("recorder-running") { Menu(ScenarioState.Unavailable, recorder = RecorderState(Recording.Running(TITLE, 12, 47), "1")) },
            case("recorder-recorded") {
                Menu(ScenarioState.Unavailable, recorder = RecorderState(Recording.Recorded(TITLE, 47, RECORDED, ListAfter.Same), "1"))
            },
            case("recorder-stopped-left") {
                val stopped = Recording.Stopped(TITLE, "score_set", Halt.Refused(429), 31, RUN, ListAfter.Left)
                Menu(ScenarioState.Unavailable, recorder = RecorderState(stopped, "1"))
            },
            case("recorder-leftover-removed") {
                Menu(ScenarioState.Unavailable, recorder = RecorderState(Recording.Leftover(TITLE, ListAfter.Removed, null)))
            },
            case(
                "recorder-refused",
            ) { Menu(ScenarioState.Unavailable, recorder = RecorderState(Recording.NotStarted(Halt.OnTheList), "21")) },
            case("tab-on-list") { OverList(Scenario()) },
            case("marker-on-list") { OverList(Scenario(offline = true)) },
            case("marker-several-on-list") { OverList(several) },
        )

    private companion object {
        const val HASH = "72e26f6"
        const val SIGN_IN = "sign-in"
        val BUILD = BuildInfo("preview", "0.1.0", Commit(HASH, dirty = false), Instant.parse("2026-11-12T09:41:00Z"))
        val TITLE = RecordedTitle(1, "Cowboy Bebop", 26)
        val RECORDED = File("/storage/emulated/0/Android/data/io.github.kauri_off.kiroku.debug/files/account.recorded")
        val RUN = File("/storage/emulated/0/Android/data/io.github.kauri_off.kiroku.debug/files/account.recorded-run")
    }

    // The sections of the running menu, in its order, each with a state given here.
    @Composable
    private fun Menu(
        scenarios: ScenarioState,
        build: BuildInfo = BUILD,
        source: SourceState =
            (
                if (scenarios ==
                    ScenarioState.Unavailable
                ) {
                    DataSource.Account
                } else {
                    DataSource.Demo
                }
            ).let { SourceState(it, it) },
        signOut: SignOutState =
            if (scenarios == ScenarioState.Unavailable) SignOutState.Account(Session.SignedIn(SignInId(SIGN_IN))) else SignOutState.Demo,
        recorder: RecorderState = RecorderState(if (scenarios == ScenarioState.Unavailable) Recording.Idle else null),
    ) = DebugMenuScreen(
        listOf(
            { ScenarioSection(scenarios, onEvent = {}) },
            { SourceSection(source, onEvent = {}) },
            { SignOutSection(signOut, onEvent = {}) },
            { RecorderSection(recorder, onEvent = {}) },
            { BuildSection(build) },
            { DesignSection(onGallery = {}) },
        ),
        onClose = {},
    )
}

// The tab where the app shows it, over the List.
@Composable
private fun OverList(scenario: Scenario) =
    Box(Modifier.fillMaxSize()) {
        FixtureListScreen(ListFixtureStates.watching)
        EdgeMarker(scenario, Modifier, onClick = {})
    }
