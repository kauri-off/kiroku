package io.github.kauri_off.kiroku.debugmenu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kauri_off.kiroku.airingcounts.AiringCountsSection
import io.github.kauri_off.kiroku.airingcounts.AiringCountsState
import io.github.kauri_off.kiroku.airingcounts.airingCounts
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.data.recorder.Halt
import io.github.kauri_off.kiroku.data.recorder.ListAfter
import io.github.kauri_off.kiroku.data.recorder.RecordedTitle
import io.github.kauri_off.kiroku.data.recorder.Recording
import io.github.kauri_off.kiroku.list.FixtureListScreen
import io.github.kauri_off.kiroku.list.ListFixtureStates
import io.github.kauri_off.kiroku.notificationsamples.NotificationSamplesSection
import io.github.kauri_off.kiroku.notificationsamples.NotificationSamplesState
import io.github.kauri_off.kiroku.recorder.RecorderSection
import io.github.kauri_off.kiroku.recorder.RecorderState
import io.github.kauri_off.kiroku.reports.Report
import io.github.kauri_off.kiroku.reports.ReportsSection
import io.github.kauri_off.kiroku.scenario.AniListFault
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
import java.time.Duration
import java.time.Instant

class DebugMenuScreenshots : ScreenshotRegistry() {
    private val several =
        Scenario(offline = true, slow = true, writes = WriteFault.Refused, store = StoreFault.WritesFail, aniList = AniListFault.Fails)

    override val content = contentCase { Menu(ScenarioState.Ready(Scenario())) }
    override val loading =
        notShown(
            "The menu reads its switches from memory and its build from the package; the airing counts appear once read, with nothing before",
        )
    override val empty = notShown("The menu always has its build and the gallery; off the demo its scenarios say why there are none")
    override val error = notShown("The menu makes no request that can fail; a store it cannot read is the airing-counts-unreadable case")

    override val cases =
        listOf(
            case("several-on") { Menu(ScenarioState.Ready(several)) },
            case("waiting-for-save") { Menu(ScenarioState.Ready(several, ResetState.WaitingForSave)) },
            case("save-dropped") { Menu(ScenarioState.Ready(Scenario(), ResetState.SaveDropped)) },
            case("real-account") { Menu(ScenarioState.Unavailable) },
            case("sandbox") {
                Menu(
                    ScenarioState.Ready(several.copy(store = StoreFault.None, aniList = AniListFault.NoAnswer), sandbox = true),
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
            case("airing-counts-stopped") { Menu(ScenarioState.Unavailable, airingCounts = STOPPED) },
            case("airing-counts-unreadable") { Menu(ScenarioState.Unavailable, airingCounts = AiringCountsState.Unreadable) },
            case("notification-samples-blocked") { Menu(ScenarioState.Unavailable, samples = NotificationSamplesState(shown = false)) },
            case("reports") { Menu(ScenarioState.Unavailable, reports = REPORTS) },
            case("reports-renamed") { Menu(ScenarioState.Unavailable, reports = RENAMED, renamed = true) },
            case("tab-on-list") { OverList(Scenario()) },
            case("marker-on-list") { OverList(Scenario(offline = true)) },
            case("marker-several-on-list") { OverList(several) },
        )

    private companion object {
        const val HASH = "72e26f6"
        const val SIGN_IN = "sign-in"
        val BUILD = BuildInfo("preview", "0.1.0", Commit(HASH, dirty = false), Instant.parse("2026-11-12T09:41:00Z"))
        val TITLE = RecordedTitle(1, "Cowboy Bebop", 26)
        val FIXTURES = Fixtures.reference
        val COUNTS = AiringCountsState.Read(airingCounts(FIXTURES.entries, FIXTURES.airing, FIXTURES.schedules, TitleLanguage.Romaji))

        // The fixtures, with one title whose schedule stopped four weeks and a day ago and one the calculator gave no result.
        val STOPPED: AiringCountsState =
            FIXTURES.now.minus(Duration.ofDays(29)).let { last ->
                val anime = FixtureAnime.exactOnBreak
                val schedule = AiringSchedule((1..6).map { ScheduledEpisode(it, last.minus(Duration.ofDays(7L * (6 - it)))) }, 13)
                AiringCountsState.Read(
                    airingCounts(
                        FIXTURES.entries,
                        FIXTURES.airing + (anime.id to airingAt(anime, schedule, FIXTURES.now)) - FixtureAnime.exactToWatch.id,
                        FIXTURES.schedules + (anime.id to schedule),
                        TitleLanguage.Romaji,
                    ),
                )
            }
        val RECORDED = File("/storage/emulated/0/Android/data/io.github.kauri_off.kiroku.debug/files/account.recorded")
        val RUN = File("/storage/emulated/0/Android/data/io.github.kauri_off.kiroku.debug/files/account.recorded-run")
        val RENAMED =
            listOf(
                Report(
                    Instant.parse("2026-11-12T09:40:00Z"),
                    listOf(
                        "Unexpected: o3.k",
                        "Stands for: q2.c",
                        "\tat o3.f.c(Unknown Source:41)",
                        "\tat o3.f.e(Unknown Source:7)",
                        "\tat o3.e.u(Unknown Source:15)",
                    ),
                ),
            )
        val REPORTS =
            listOf(
                Report(
                    Instant.parse("2026-11-12T09:40:00Z"),
                    listOf(
                        "Unexpected: io.github.kauri_off.kiroku.data.network.RepairedAnswer",
                        "\tat io.github.kauri_off.kiroku.data.list.MalRemoteList.read(MalRemoteList.kt:91)",
                        "\tat io.github.kauri_off.kiroku.data.list.MalRemoteList.access\$read(MalRemoteList.kt:39)",
                        "\tat io.github.kauri_off.kiroku.data.list.MalRemoteList\$set\$1.invokeSuspend(Unknown Source:15)",
                    ),
                ),
                Report(
                    Instant.parse("2026-11-12T08:12:00Z"),
                    listOf(
                        "Unexpected: java.lang.IllegalStateException",
                        "\tat io.github.kauri_off.kiroku.data.db.RoomLocalList.read(RoomLocalList.kt:88)",
                        "Caused by: java.lang.IllegalArgumentException",
                        "\tat io.github.kauri_off.kiroku.data.db.TitleRow.toAnime(TitleRow.kt:54)",
                    ),
                ),
            )
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
        reports: List<Report> = emptyList(),
        renamed: Boolean = false,
        airingCounts: AiringCountsState = COUNTS,
        samples: NotificationSamplesState = NotificationSamplesState(),
    ) = DebugMenuScreen(
        listOf(
            { ScenarioSection(scenarios, onEvent = {}) },
            { SourceSection(source, onEvent = {}) },
            { SignOutSection(signOut, onEvent = {}) },
            { RecorderSection(recorder, onEvent = {}) },
            { AiringCountsSection(airingCounts, onEvent = {}) },
            { NotificationSamplesSection(samples, onEvent = {}) },
            { BuildSection(build) },
            { DesignSection(onGallery = {}) },
            { ReportsSection(reports, renamed) },
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
