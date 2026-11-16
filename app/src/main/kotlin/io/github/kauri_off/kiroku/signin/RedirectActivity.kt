package io.github.kauri_off.kiroku.signin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.wiring.appGraph

// Takes MAL's redirect: hands it to the sign-in, brings the app's screen back over the browser's tab, and is gone.
class RedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Neither the activity made again nor an intent the system replays from the recent tasks is a redirect.
        val arrived = savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        if (arrived) appGraph.signIn.redirected(intent.dataString.orEmpty())
        // In the app's own task: a browser that starts this activity in its own task would otherwise get a second screen.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
