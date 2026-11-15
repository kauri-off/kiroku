package io.github.kauri_off.kiroku.link

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.link.animeLink

// Takes a link to a title's page on MAL: hands the title to the app's screen, brings that screen up, and is gone.
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Neither the activity made again nor an intent the system replays from the recent tasks is a link.
        val arrived = savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        val id = if (arrived) intent.dataString?.let(::animeLink) else null
        // Any other address opens nothing of the app's.
        if (id != null || !arrived) {
            id?.let { appLinks?.opened(it) }
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }
}
