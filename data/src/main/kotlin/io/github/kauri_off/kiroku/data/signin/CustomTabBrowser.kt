package io.github.kauri_off.kiroku.data.signin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import io.github.kauri_off.kiroku.core.repository.SignInBrowser

// S1: a Custom Tab of the user's browser in the activity's task, or whatever opens a web address; never a WebView.
class CustomTabBrowser(
    private val activity: Activity,
) : SignInBrowser {
    override fun open(address: String): Boolean {
        val tab = CustomTabsIntent.Builder().build()
        CustomTabsClient.getPackageName(activity, emptyList())?.let(tab.intent::setPackage)
        return try {
            tab.launchUrl(activity, Uri.parse(address))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
