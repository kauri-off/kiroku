package io.github.kauri_off.kiroku.debugmenu

import androidx.compose.runtime.Composable
import io.github.kauri_off.kiroku.airingcounts.AiringCountsRoute
import io.github.kauri_off.kiroku.notificationsamples.NotificationSamplesRoute
import io.github.kauri_off.kiroku.recorder.RecorderRoute
import io.github.kauri_off.kiroku.reports.ReportsRoute
import io.github.kauri_off.kiroku.scenario.ScenarioRoute
import io.github.kauri_off.kiroku.signout.SignOutRoute
import io.github.kauri_off.kiroku.source.SourceRoute

// The menu's sections in their order. Each is one file with its own strings file; a new tool adds its line here.
val debugMenuSections: List<@Composable () -> Unit> =
    listOf(
        { ScenarioRoute() },
        { SourceRoute() },
        { SignOutRoute() },
        { RecorderRoute() },
        { AiringCountsRoute() },
        { NotificationSamplesRoute() },
        { BuildRoute() },
        { DesignRoute() },
        { ReportsRoute() },
    )
