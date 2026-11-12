package io.github.kauri_off.kiroku.scenario

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.debugmenu.DebugMenuActivity

// A quick settings tile that opens the debug menu from anywhere, and shows which scenarios are on.
class ScenarioTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val scenario = scenarios?.scenario?.value
        tile.state =
            when {
                scenario == null -> Tile.STATE_UNAVAILABLE
                scenario.active -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val names = scenario?.names().orEmpty()
            tile.subtitle =
                if (names.isEmpty()) {
                    getString(
                        R.string.scenarios_off,
                    )
                } else {
                    names.joinToString(getString(R.string.common_separator)) { getString(it) }
                }
        }
        tile.updateTile()
    }

    // Below Android 14 only the deprecated overload exists, and it works there.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = DebugMenuActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
