package io.github.kauri_off.kiroku.wiring

import android.app.Application
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.reports.reportLog
import io.github.kauri_off.kiroku.scenario.ScenarioOverlay
import java.io.File

// The debug menu's tab over the app's screens, and the file the reports of every start are kept in.
internal val debugTools: ((Application) -> Unit)? = { app ->
    ScenarioOverlay.install(app)
    reportLog.keepIn(File(app.noBackupFilesDir, notShown("reports")))
}
