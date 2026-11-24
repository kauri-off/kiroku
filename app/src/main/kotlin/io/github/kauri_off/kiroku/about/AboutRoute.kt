package io.github.kauri_off.kiroku.about

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.shell.show
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object AboutKey : NavKey

@Composable
fun AboutRoute(
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.about_no_browser)
    val appName = stringResource(context.applicationInfo.labelRes.takeIf { it != 0 } ?: R.string.app_name)

    fun openLink(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbars.show(noBrowser, action = null) }
        }
    }

    AboutScreen(
        state = AboutState(appName, BuildConfig.VERSION_NAME),
        onEvent = { event ->
            when (event) {
                AboutEvent.Back -> onBack()
                AboutEvent.OpenProject -> openLink(PROJECT_URL)
                AboutEvent.OpenLicense -> openLink(LICENSE_URL)
                AboutEvent.OpenPrivacy -> openLink(PRIVACY_URL)
            }
        },
        snackbars = snackbars,
    )
}

private val PROJECT_URL = notShown("https://github.com/kauri-off/kiroku")
private val LICENSE_URL = notShown("$PROJECT_URL/blob/main/LICENSE")
private val PRIVACY_URL = notShown("$PROJECT_URL/blob/main/PRIVACY.md")
