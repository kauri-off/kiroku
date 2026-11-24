package io.github.kauri_off.kiroku.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.titlesub.SubScreen
import io.github.kauri_off.kiroku.titlesub.SubScreenBody

data class AboutState(
    val appName: String,
    val versionName: String,
)

enum class AboutEvent { Back, OpenProject, OpenLicense, OpenPrivacy }

@Composable
fun AboutScreen(
    state: AboutState,
    onEvent: (AboutEvent) -> Unit,
    snackbars: SnackbarHostState,
) {
    SubScreen(
        title = stringResource(R.string.about_title),
        sessionExpired = false,
        onBack = { onEvent(AboutEvent.Back) },
        onSignIn = {},
        snackbars = snackbars,
        body =
            SubScreenBody.List {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xl),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(state.appName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.about_version, state.versionName), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
                item(key = AboutEvent.OpenProject) {
                    AboutLink(stringResource(R.string.about_project), onClick = { onEvent(AboutEvent.OpenProject) })
                }
                item(key = AboutEvent.OpenLicense) {
                    AboutLink(
                        stringResource(R.string.about_license),
                        detail = stringResource(R.string.about_license_name),
                        onClick = { onEvent(AboutEvent.OpenLicense) },
                    )
                }
                item(key = AboutEvent.OpenPrivacy) {
                    AboutLink(stringResource(R.string.about_privacy), onClick = { onEvent(AboutEvent.OpenPrivacy) })
                }
            },
    )
}

@Composable
private fun AboutLink(
    label: String,
    onClick: () -> Unit,
    detail: String? = null,
) {
    val spoken =
        if (detail == null) {
            stringResource(R.string.about_open_link, label)
        } else {
            stringResource(R.string.about_open_link_with_detail, label, detail)
        }
    ListItem(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = spoken },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null) },
        content = { Text(label) },
    )
}
