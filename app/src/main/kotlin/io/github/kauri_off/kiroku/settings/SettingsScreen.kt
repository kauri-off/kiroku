package io.github.kauri_off.kiroku.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.titlesub.SubScreen
import io.github.kauri_off.kiroku.titlesub.SubScreenBody
import kotlinx.serialization.Serializable

@Serializable
data object SettingsKey : NavKey

data class SettingsState(
    val language: TitleLanguage?,
    val adultInSearch: Boolean?,
    val appLanguageAvailable: Boolean,
)

sealed interface SettingsEvent {
    data object Back : SettingsEvent

    data object OpenNotifications : SettingsEvent

    data object OpenAppLanguage : SettingsEvent

    data class SetTitleLanguage(
        val language: TitleLanguage,
    ) : SettingsEvent

    data object ToggleAdultInSearch : SettingsEvent
}

@Composable
fun SettingsScreen(
    state: SettingsState,
    onEvent: (SettingsEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    var chooseLanguage by rememberSaveable { mutableStateOf(false) }
    val language = state.language
    SubScreen(
        title = stringResource(R.string.settings_title),
        sessionExpired = false,
        onBack = { onEvent(SettingsEvent.Back) },
        onSignIn = {},
        snackbars = snackbars,
        body =
            SubScreenBody.List {
                if (state.appLanguageAvailable) {
                    item {
                        ListItem(
                            onClick = { onEvent(SettingsEvent.OpenAppLanguage) },
                            content = { Text(stringResource(R.string.settings_app_language)) },
                        )
                    }
                }
                item {
                    ListItem(
                        onClick = { chooseLanguage = true },
                        enabled = language != null,
                        supportingContent = language?.let { { Text(stringResource(it.label)) } },
                        content = { Text(stringResource(R.string.settings_title_language)) },
                    )
                }
                item {
                    val checked = state.adultInSearch
                    ListItem(
                        modifier =
                            Modifier.toggleable(
                                value = checked == true,
                                enabled = checked != null,
                                role = Role.Switch,
                                onValueChange = { onEvent(SettingsEvent.ToggleAdultInSearch) },
                            ),
                        trailingContent = { Switch(checked = checked == true, onCheckedChange = null, enabled = checked != null) },
                        content = { Text(stringResource(R.string.settings_adult_in_search)) },
                    )
                }
                item {
                    ListItem(
                        onClick = { onEvent(SettingsEvent.OpenNotifications) },
                        supportingContent = { Text(stringResource(R.string.settings_notifications_detail)) },
                        content = { Text(stringResource(R.string.settings_notifications)) },
                    )
                }
            },
        modifier = modifier,
    )
    if (chooseLanguage && language != null) {
        TitleLanguageDialog(
            language,
            dismiss = { chooseLanguage = false },
            choose = {
                chooseLanguage = false
                onEvent(SettingsEvent.SetTitleLanguage(it))
            },
        )
    }
}

@Composable
internal fun TitleLanguageDialog(
    current: TitleLanguage,
    dismiss: () -> Unit,
    choose: (TitleLanguage) -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.settings_title_language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                TitleLanguage.entries.forEach { language ->
                    ListItem(
                        modifier = Modifier.selectable(selected = language == current, role = Role.RadioButton) { choose(language) },
                        leadingContent = { RadioButton(selected = language == current, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        content = { Text(stringResource(language.label)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

private val TitleLanguage.label: Int
    get() =
        when (this) {
            TitleLanguage.Romaji -> R.string.settings_romaji
            TitleLanguage.English -> R.string.settings_english
            TitleLanguage.Japanese -> R.string.settings_japanese
        }
