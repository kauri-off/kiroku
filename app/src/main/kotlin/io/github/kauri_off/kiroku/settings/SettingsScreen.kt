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
import io.github.kauri_off.kiroku.core.model.DisplayPreferences
import io.github.kauri_off.kiroku.core.model.StartupTab
import io.github.kauri_off.kiroku.core.model.ThemeMode
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.design.theme.DialogSystemBarAppearance
import io.github.kauri_off.kiroku.shell.destination
import io.github.kauri_off.kiroku.titlesub.SubScreen
import io.github.kauri_off.kiroku.titlesub.SubScreenBody
import kotlinx.serialization.Serializable

@Serializable
data object SettingsKey : NavKey

data class SettingsState(
    val language: TitleLanguage?,
    val adultInSearch: Boolean?,
    val appLanguageAvailable: Boolean,
    val display: DisplayPreferences?,
)

sealed interface SettingsEvent {
    data object Back : SettingsEvent

    data object OpenNotifications : SettingsEvent

    data object OpenAbout : SettingsEvent

    data object OpenAppLanguage : SettingsEvent

    data class SetTitleLanguage(
        val language: TitleLanguage,
    ) : SettingsEvent

    data class SetTheme(
        val mode: ThemeMode,
    ) : SettingsEvent

    data class SetStartupTab(
        val tab: StartupTab,
    ) : SettingsEvent

    data object ToggleAdultInSearch : SettingsEvent
}

private enum class Picker { Theme, StartupTab, TitleLanguage }

@Composable
fun SettingsScreen(
    state: SettingsState,
    onEvent: (SettingsEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    val language = state.language
    val display = state.display
    val dismiss = { picker = null }
    SubScreen(
        title = stringResource(R.string.settings_title),
        sessionExpired = false,
        onBack = { onEvent(SettingsEvent.Back) },
        onSignIn = {},
        snackbars = snackbars,
        body =
            SubScreenBody.List {
                item {
                    ChoiceRow(R.string.settings_theme, display?.theme?.label) { picker = Picker.Theme }
                }
                item {
                    ChoiceRow(R.string.settings_startup_tab, display?.startupTab?.label) { picker = Picker.StartupTab }
                }
                if (state.appLanguageAvailable) {
                    item {
                        ListItem(
                            onClick = { onEvent(SettingsEvent.OpenAppLanguage) },
                            content = { Text(stringResource(R.string.settings_app_language)) },
                        )
                    }
                }
                item {
                    ChoiceRow(R.string.settings_title_language, language?.label) { picker = Picker.TitleLanguage }
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
                item {
                    ListItem(
                        onClick = { onEvent(SettingsEvent.OpenAbout) },
                        content = { Text(stringResource(R.string.about_title)) },
                    )
                }
            },
        modifier = modifier,
    )

    fun choose(event: SettingsEvent) {
        dismiss()
        onEvent(event)
    }
    when (picker) {
        Picker.Theme -> if (display != null) ThemeDialog(display.theme, dismiss) { choose(SettingsEvent.SetTheme(it)) }
        Picker.StartupTab -> if (display != null) StartupTabDialog(display.startupTab, dismiss) { choose(SettingsEvent.SetStartupTab(it)) }
        Picker.TitleLanguage -> if (language != null) TitleLanguageDialog(language, dismiss) { choose(SettingsEvent.SetTitleLanguage(it)) }
        null -> Unit
    }
}

@Composable
private fun ChoiceRow(
    title: Int,
    selected: Int?,
    onClick: () -> Unit,
) {
    ListItem(
        onClick = onClick,
        enabled = selected != null,
        supportingContent = selected?.let { { Text(stringResource(it)) } },
        content = { Text(stringResource(title)) },
    )
}

@Composable
internal fun ThemeDialog(
    current: ThemeMode,
    dismiss: () -> Unit,
    choose: (ThemeMode) -> Unit,
) = ChoiceDialog(R.string.settings_theme, ThemeMode.entries, current, { it.label }, dismiss, choose)

@Composable
internal fun StartupTabDialog(
    current: StartupTab,
    dismiss: () -> Unit,
    choose: (StartupTab) -> Unit,
) = ChoiceDialog(R.string.settings_startup_tab, StartupTab.entries, current, { it.label }, dismiss, choose)

@Composable
internal fun TitleLanguageDialog(
    current: TitleLanguage,
    dismiss: () -> Unit,
    choose: (TitleLanguage) -> Unit,
) = ChoiceDialog(R.string.settings_title_language, TitleLanguage.entries, current, { it.label }, dismiss, choose)

@Composable
private fun <T> ChoiceDialog(
    title: Int,
    options: List<T>,
    current: T,
    label: (T) -> Int,
    dismiss: () -> Unit,
    choose: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(title)) },
        text = {
            DialogSystemBarAppearance()
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                options.forEach { option ->
                    val selected = option == current
                    ListItem(
                        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { choose(option) },
                        leadingContent = { RadioButton(selected = selected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        content = { Text(stringResource(label(option))) },
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

private val ThemeMode.label: Int
    get() =
        when (this) {
            ThemeMode.System -> R.string.settings_theme_system
            ThemeMode.Light -> R.string.settings_theme_light
            ThemeMode.Dark -> R.string.settings_theme_dark
            ThemeMode.PureBlack -> R.string.settings_theme_pure_black
        }

private val StartupTab.label: Int get() = destination.label
