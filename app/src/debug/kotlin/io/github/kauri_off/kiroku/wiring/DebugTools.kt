package io.github.kauri_off.kiroku.wiring

import android.app.Application
import io.github.kauri_off.kiroku.scenario.ScenarioOverlay

// The debug build's scenario marker over the app's screens.
internal val debugTools: ((Application) -> Unit)? = ScenarioOverlay::install
