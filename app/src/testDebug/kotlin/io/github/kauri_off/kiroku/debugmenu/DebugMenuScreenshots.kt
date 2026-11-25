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
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.Recheck
import io.github.kauri_off.kiroku.core.notify.RunEnd
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.data.recorder.Halt
import io.github.kauri_off.kiroku.data.recorder.ListAfter
import io.github.kauri_off.kiroku.data.recorder.RecordedTitle
import io.github.kauri_off.kiroku.data.recorder.Recording
import io.github.kauri_off.kiroku.data.sandbox.Planted
import io.github.kauri_off.kiroku.list.FixtureListScreen
import io.github.kauri_off.kiroku.list.ListFixtureStates
import io.github.kauri_off.kiroku.notificationlog.LoggedPost
import io.github.kauri_off.kiroku.notificationlog.LoggedRun
import io.github.kauri_off.kiroku.notificationlog.NotificationLogSection
import io.github.kauri_off.kiroku.notificationlog.NotificationLogState
import io.github.kauri_off.kiroku.notificationlog.PlantRefusal
import io.github.kauri_off.kiroku.notificationlog.PlantState
import io.github.kauri_off.kiroku.notificationsamples.NotificationSamplesSection
import io.github.kauri_off.kiroku.notificationsamples.NotificationSamplesState
import io.github.kauri_off.kiroku.recorder.RecorderSection
import io.github.kauri_off.kiroku.recorder.RecorderState
import io.github.kauri_off.kiroku.reports.Report
import io.github.kauri_off.kiroku.reports.ReportsSection
import io.github.kauri_off.kiroku.scenario.AniListFault
import io.github.kauri_off.kiroku.scenario.EdgeMarker
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

    // Each case sets every section it can, so a section's states share cases rather than each having its own.
    override val cases =
        listOf(
            case("faults-on") { Menu(ScenarioState.Ready(several), source = SourceState(DataSource.Demo, DataSource.Demo, kept = false)) },
            case("sandbox") {
                Menu(
                    ScenarioState.Ready(several.copy(store = StoreFault.None, aniList = AniListFault.NoAnswer), sandbox = true),
                    source = SourceState(DataSource.Sandbox, DataSource.Sandbox),
                    signOut = SignOutState.Account(Session.SignedIn(SignInId(SIGN_IN))),
                    recorder = RecorderState(null),
                    runs =
                        NotificationLogState(
                            RUNS,
                            plant = PlantState(PLANTED_TITLE, Planted(5, AT.plusSeconds(600), AT.plusSeconds(1500)), PlantRefusal.NoneLeft),
                            minutes = "15",
                        ),
                )
            },
            case("real-account") {
                Menu(
                    ScenarioState.Unavailable,
                    BUILD.copy(commit = Commit(HASH, dirty = true)),
                    source = SourceState(DataSource.Account, chosen = DataSource.Demo),
                    reports = REPORTS,
                    runs = NotificationLogState(MANY_RUNS),
                )
            },
            case("recorder-confirm") { Menu(ScenarioState.Unavailable, recorder = RecorderState(Recording.Checked(TITLE), "1")) },
            case("recorder-read-running") {
                Menu(ScenarioState.Unavailable, recorder = RecorderState(Recording.Reading))
            },
            case("recorder-read-finished") {
                val output =
                    File(
                        "/data/user/0/io.github.kauri_off.kiroku.debug/no_backup/" +
                            "account.private-reads/suggestions/capture-00000000-0000-0000-0000-000000000000",
                    )
                Menu(ScenarioState.Unavailable, recorder = RecorderState(Recording.ReadRecorded(2, output)))
            },
            case("recorder-running") {
                Menu(
                    ScenarioState.Unavailable,
                    recorder = RecorderState(Recording.Running(TITLE, 12, 47), "1"),
                    airingCounts = AiringCountsState.Unreadable,
                    samples = NotificationSamplesState(shown = false),
                    runs =
                        NotificationLogState(
                            listOf(LoggedRun.notBuilt(RunTrigger.AppOpened, AT.plusSeconds(60))) + RUNS.take(1),
                            plant = PlantState(PLANTED_TITLE, Planted(4, AT.plusSeconds(300))),
                        ),
                )
            },
            case("recorder-stopped-left") {
                val stopped = Recording.Stopped(TITLE, "score_set", Halt.Refused(429), 31, RUN, ListAfter.Left)
                Menu(
                    ScenarioState.Unavailable,
                    BUILD.copy(commit = null, built = null, hasClientId = false),
                    recorder = RecorderState(stopped, "1"),
                    airingCounts = STOPPED,
                )
            },
            case("tab-on-list") { OverList(Scenario()) },
            case("marker-several-on-list") { OverList(several) },
        )

    private companion object {
        const val HASH = "72e26f6"
        const val SIGN_IN = "sign-in"
        val BUILD = BuildInfo("preview", "0.1.0", Commit(HASH, dirty = false), Instant.parse("2026-11-12T09:41:00Z"), hasClientId = true)
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
        val AT: Instant = Instant.parse("2026-11-12T09:30:00Z")
        val PLANTED_TITLE = FixtureAnime.exactToWatch.titles.romaji

        private fun post(
            anime: Anime,
            episode: Int,
            notifyAt: Instant,
            postedAt: Instant,
            shown: Boolean = true,
            delay: Duration = Duration.ZERO,
        ) = LoggedPost(anime.id, anime.titles.romaji, episode, notifyAt.minus(delay), notifyAt, postedAt, shown)

        // Runs the engine could have made, newest first: each post inside its run, after its notification time.
        val RUNS =
            listOf(
                LoggedRun(
                    RunTrigger.Alarm,
                    AT,
                    AT.plusMillis(5400),
                    RunEnd.Done,
                    Recheck.Late,
                    listOf(
                        post(FixtureAnime.exactToWatch, 4, AT, AT.plusMillis(5300)),
                        post(FixtureAnime.longTitle, 7, AT, AT.plusMillis(5350), shown = false, delay = Duration.ofMinutes(30)),
                    ),
                    emptyList(),
                    AT.plusSeconds(604800),
                    alarmFailed = false,
                ),
                LoggedRun(
                    RunTrigger.Changed,
                    AT.minusSeconds(40),
                    AT.minusSeconds(40),
                    RunEnd.Done,
                    Recheck.NotAsked,
                    emptyList(),
                    listOf(FixtureAnime.exactOnBreak.id, FixtureAnime.estimatedToWatch.id),
                    AT,
                    false,
                ),
                LoggedRun(
                    RunTrigger.DeviceRestarted,
                    AT.minusSeconds(4000),
                    AT.minusSeconds(3999),
                    RunEnd.Failed,
                    Recheck.NotAsked,
                    listOf(post(FixtureAnime.exactOnBreak, 2, AT.minusSeconds(4000 + 7380), AT.minusSeconds(4000).plusMillis(500))),
                    emptyList(),
                    null,
                    true,
                ),
                LoggedRun(
                    RunTrigger.Alarm,
                    AT.minusSeconds(7000),
                    AT.minusSeconds(6997),
                    RunEnd.Done,
                    Recheck.Answered,
                    emptyList(),
                    emptyList(),
                    AT,
                    false,
                ),
                LoggedRun(
                    RunTrigger.Changed,
                    AT.minusSeconds(9000),
                    AT.minusSeconds(8999),
                    RunEnd.SignedOut,
                    Recheck.NotAsked,
                    emptyList(),
                    emptyList(),
                    null,
                    false,
                    forgotten = 2,
                ),
            )

        // More runs than are shown at once, as days of use leave them.
        val MANY_RUNS =
            (0 until 34).map { i ->
                LoggedRun(
                    RunTrigger.entries[i % RunTrigger.entries.size],
                    AT.minusSeconds(3600L * i),
                    AT.minusSeconds(3600L * i).plusMillis(120),
                    RunEnd.Done,
                    if (i % RunTrigger.entries.size == 0) Recheck.Offline else Recheck.NotAsked,
                    emptyList(),
                    emptyList(),
                    AT.plusSeconds(3600),
                    false,
                )
            }
        val RUN = File("/storage/emulated/0/Android/data/io.github.kauri_off.kiroku.debug/files/account.recorded-run")
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
        airingCounts: AiringCountsState = COUNTS,
        samples: NotificationSamplesState = NotificationSamplesState(),
        runs: NotificationLogState = NotificationLogState(emptyList()),
    ) = DebugMenuScreen(
        listOf(
            { ScenarioSection(scenarios, onEvent = {}) },
            { SourceSection(source, onEvent = {}) },
            { SignOutSection(signOut, onEvent = {}) },
            { RecorderSection(recorder, onEvent = {}) },
            { AiringCountsSection(airingCounts, onEvent = {}) },
            { NotificationSamplesSection(samples, onEvent = {}) },
            { NotificationLogSection(runs, onEvent = {}) },
            { BuildSection(build) },
            { DesignSection(onGallery = {}) },
            { ReportsSection(reports, renamed = false) },
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
