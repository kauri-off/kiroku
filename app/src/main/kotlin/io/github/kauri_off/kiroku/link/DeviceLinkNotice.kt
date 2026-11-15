package io.github.kauri_off.kiroku.link

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.kauri_off.kiroku.core.link.animeLinkHosts
import io.github.kauri_off.kiroku.data.preferences.StoredNotice
import io.github.kauri_off.kiroku.notShown

// The notice is due while [needsConsent] says the system does not open the links in the app, until it is shown.
class DeviceLinkNotice(
    private val store: StoredNotice,
    private val needsConsent: () -> Boolean,
) : LinkNotice {
    override suspend fun due(): Boolean = needsConsent() && !store.shown()

    override suspend fun shown() = store.markShown()
}

// Android 12 and later open a link to a host it cannot verify for the app only once the person allowed that host.
fun linksNeedConsent(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val state =
        context.getSystemService(DomainVerificationManager::class.java)?.getDomainVerificationUserState(context.packageName)
            ?: return true
    val opened = setOf(DomainVerificationUserState.DOMAIN_STATE_SELECTED, DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
    return !state.isLinkHandlingAllowed || animeLinkHosts.any { state.hostToStateMap[it] !in opened }
}

// The system's screen of the links this app opens; where the system has none, the app's own settings.
fun Context.openLinkSettings() {
    val app = Uri.fromParts(notShown("package"), packageName, null)
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)
    val links = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, app) else details
    try {
        startActivity(links)
    } catch (_: ActivityNotFoundException) {
        startActivity(details)
    }
}
