package io.github.kauri_off.kiroku.data.signin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import io.github.kauri_off.kiroku.core.repository.SignInBrowser

// S1: a Custom Tab of the user's browser where it has them, otherwise whatever opens a web address; never a WebView.
class CustomTabBrowser(
    private val context: Context,
) : SignInBrowser {
    override fun open(address: String): Boolean {
        val tab = CustomTabsIntent.Builder().build()
        CustomTabsClient.getPackageName(context, emptyList())?.let(tab.intent::setPackage)
        if (context !is Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            tab.launchUrl(context, Uri.parse(address))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
