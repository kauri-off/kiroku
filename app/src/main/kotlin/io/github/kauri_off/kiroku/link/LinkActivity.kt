package io.github.kauri_off.kiroku.link

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.link.readLink

// Takes a link to a title's or a profile's page on MAL: hands it to the app's screen and brings it up, or hands it on.
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Neither the activity made again nor an intent the system replays from the recent tasks is a link.
        val arrived = savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        val address = intent.data
        val link = if (arrived) intent.dataString?.let(::readLink) else null
        when {
            link != null -> {
                appLinks?.opened(link)
                bringUp()
            }
            !arrived -> bringUp()
            address != null -> handOn(address)
        }
        finish()
    }

    private fun bringUp() =
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )

    // An address the filter matched and the app does not open goes to the browser; this app does not show itself.
    private fun handOn(address: Uri) {
        val browser =
            Intent(Intent.ACTION_VIEW, address)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        browser.selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        try {
            startActivity(browser)
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}
