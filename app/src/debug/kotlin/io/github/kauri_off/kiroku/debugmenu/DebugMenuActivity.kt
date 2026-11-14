package io.github.kauri_off.kiroku.debugmenu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import io.github.kauri_off.kiroku.design.theme.setKirokuContent

// The debug menu, over whatever screen of the app was open; Back returns to it.
class DebugMenuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setKirokuContent { DebugMenuScreen(debugMenuSections, onClose = ::finish) }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, DebugMenuActivity::class.java)
    }
}
