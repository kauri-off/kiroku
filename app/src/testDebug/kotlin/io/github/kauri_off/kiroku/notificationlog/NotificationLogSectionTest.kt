package io.github.kauri_off.kiroku.notificationlog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.notify.Recheck
import io.github.kauri_off.kiroku.core.notify.RunEnd
import io.github.kauri_off.kiroku.core.notify.RunReport
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.notify.SentNotification
import io.github.kauri_off.kiroku.data.sandbox.Planted
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor

// The section the emulator run and the owner's days are read from: a line per run, with its trigger and each post's delay.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class NotificationLogSectionTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @get:Rule
    val folder = TemporaryFolder()

    private val events = mutableListOf<NotificationLogEvent>()

    private fun show(state: NotificationLogState) =
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { NotificationLogSection(state) { events += it } } }

    // The runs of an earlier process, as the next one reads them from the file.
    private fun keptRuns(reports: List<RunReport>): List<LoggedRun> {
        val file = File(folder.root, "account.runs")
        RunLog(file, files = Executor(Runnable::run), log = {}).apply { reports.forEach(::keep) }
        return RunLog(file, files = Executor(Runnable::run), log = {}).runs.value
    }

    private val title = FixtureAnime.exactToWatch.titles.romaji

    private val reports =
        listOf(
            RunReport(
                RunTrigger.Alarm,
                AT,
                AT.plusMillis(5400),
                RunEnd.Done,
                Recheck.Late,
                listOf(SentNotification(FixtureAnime.exactToWatch, 4, AT, AT, AT.plusSeconds(3), Recheck.Late)),
                alarm = AT.plusSeconds(3600),
            ),
            RunReport(
                RunTrigger.DeviceRestarted,
                AT.plusSeconds(7380),
                AT.plusSeconds(7385),
                RunEnd.Done,
                posted =
                    listOf(
                        SentNotification(FixtureAnime.exactToWatch, 5, AT, AT, AT.plusSeconds(7384), Recheck.NotAsked, shown = false),
                    ),
                withdrawn = setOf(FixtureAnime.exactToWatch.id),
            ),
        )

    @Test
    fun `each run kept by an earlier process is one line with its trigger, its posts with their delays, and what it withdrew`() {
        show(NotificationLogState(keptRuns(reports)))

        compose.onNodeWithText("Alarm").assertExists()
        compose.onNodeWithText("Device restarted").assertExists()
        compose.onNodeWithText("Sent: $title, episode\u00A04, 3\u00A0s after its notification time", substring = true).assertExists()
        compose.onNodeWithText("Re-check: AniList answered too late, stored data used", substring = true).assertExists()
        compose
            .onNodeWithText(
                "Sent: $title, episode\u00A05, 2\u00A0h\u00A03\u00A0min after its notification time; the device did not show it",
                substring = true,
            ).assertExists()
        compose.onNodeWithText("Withdrawn: $title", substring = true).assertExists()
        compose.onNodeWithText("No alarm set", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp")
    fun `in Russian, the same`() {
        show(NotificationLogState(keptRuns(reports)))

        compose.onNodeWithText("Будильник").assertExists()
        compose
            .onNodeWithText(
                "Отправлено: $title, серия\u00A04, через\u00A03\u00A0с после времени уведомления",
                substring = true,
            ).assertExists()
        compose.onNodeWithText("Перезагрузка устройства").assertExists()
    }

    @Test
    fun `a post before its notification time, by a clock set back between the two, says so`() {
        val early = SentNotification(FixtureAnime.exactToWatch, 4, AT, AT, AT.minusSeconds(90), Recheck.NotAsked)
        show(
            NotificationLogState(
                keptRuns(
                    listOf(RunReport(RunTrigger.AppOpened, AT.minusSeconds(91), AT.minusSeconds(89), RunEnd.Done, posted = listOf(early))),
                ),
            ),
        )

        compose
            .onNodeWithText(
                "Sent: $title, episode\u00A04, 1\u00A0min\u00A030\u00A0s before its notification time",
                substring = true,
            ).assertExists()
    }

    @Test
    fun `a run that found nobody signed in counts what it withdrew, and a run whose engine was not built says so`() {
        val signedOut =
            RunReport(
                RunTrigger.Changed,
                AT,
                AT,
                RunEnd.SignedOut,
                withdrawn = setOf(FixtureAnime.exactToWatch.id, FixtureAnime.longTitle.id),
            )
        val file = File(folder.root, "account.runs")
        RunLog(file, files = Executor(Runnable::run), log = {}).apply {
            keep(signedOut)
            notBuilt(RunTrigger.Alarm, AT.plusSeconds(60))
        }
        show(NotificationLogState(RunLog(file, files = Executor(Runnable::run), log = {}).runs.value))

        compose.onNodeWithText("Withdrawn:\u00A02\u00A0titles", substring = true).assertExists()
        compose.onNodeWithText("Nobody is signed in: everything was forgotten", substring = true).assertExists()
        compose.onNodeWithText("The notification engine could not be built: nothing was done", substring = true).assertExists()
        compose.onNodeWithText("The run failed", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the latest runs are shown, and earlier ones on request`() {
        val runs = (0 until 31).map { LoggedRun.of(RunReport(RunTrigger.Changed, AT.minusSeconds(it * 60L), AT, RunEnd.Done)) }
        show(NotificationLogState(runs, shown = 30))

        compose.onNodeWithText("Show earlier runs").performScrollTo().performClick()

        assertEquals(listOf<NotificationLogEvent>(NotificationLogEvent.ShowEarlier), events)
    }

    @Test
    fun `on the sandbox an episode is planted the minutes typed ahead, and postponed only once one is planted`() {
        show(NotificationLogState(emptyList(), plant = PlantState(title, null), minutes = "15"))

        compose.onNodeWithText("Postpone at AniList").assertIsNotEnabled()
        compose.onNodeWithText("Plant an episode").performClick()

        assertEquals(listOf<NotificationLogEvent>(NotificationLogEvent.Plant), events)
        compose.onNodeWithText("Plants the next episode of $title", substring = true).assertExists()
    }

    @Test
    fun `a postponed episode says its time at AniList and the time it was planted for`() {
        val planted = Planted(4, AT, AT.plus(Duration.ofMinutes(15)))
        show(NotificationLogState(emptyList(), plant = PlantState(title, planted, PlantRefusal.Aired)))

        compose.onNodeWithText("Episode\u00A04\u00A0airs", substring = true).assertExists()
        compose.onNodeWithText("at AniList, planted for", substring = true).assertExists()
        compose.onNodeWithText("The planted episode has aired: nothing to postpone").assertExists()
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-11-18T09:30:00Z")
    }
}
