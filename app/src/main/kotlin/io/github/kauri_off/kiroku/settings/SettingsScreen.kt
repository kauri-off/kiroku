package io.github.kauri_off.kiroku.settings

import androidx.compose.material3.ListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.titlesub.SubScreen
import io.github.kauri_off.kiroku.titlesub.SubScreenBody
import kotlinx.serialization.Serializable

@Serializable
data object SettingsKey : NavKey

enum class SettingsEvent { Back, OpenNotifications }

// The app's settings, opened from the Profile: each row opens its own screen.
@Composable
fun SettingsScreen(
    onEvent: (SettingsEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.settings_title)
    val notifications = stringResource(R.string.settings_notifications)
    val detail = stringResource(R.string.settings_notifications_detail)
    SubScreen(
        title = title,
        sessionExpired = false,
        onBack = { onEvent(SettingsEvent.Back) },
        onSignIn = {},
        snackbars = snackbars,
        body =
            SubScreenBody.List {
                item(key = SettingsEvent.OpenNotifications) {
                    ListItem(
                        onClick = { onEvent(SettingsEvent.OpenNotifications) },
                        supportingContent = { Text(detail) },
                        content = { Text(notifications) },
                    )
                }
            },
        modifier = modifier,
    )
}
