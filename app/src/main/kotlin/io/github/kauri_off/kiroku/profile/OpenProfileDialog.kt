package io.github.kauri_off.kiroku.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.DialogProperties
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.WindowWithoutAutofill
import io.github.kauri_off.kiroku.design.component.withoutAutofill
import io.github.kauri_off.kiroku.design.theme.Spacing

// Asks for a user name, with the names opened last as chips that open their profile at once.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OpenProfileDialog(
    dialog: OpenProfile,
    onEvent: (ProfileEvent) -> Unit,
) {
    val name = dialog.nameToOpen()
    val open = { name?.let { onEvent(ProfileEvent.OpenProfile(it)) } }
    AlertDialog(
        onDismissRequest = { onEvent(ProfileEvent.DismissOpenProfile) },
        title = { Text(stringResource(R.string.profile_open_another)) },
        text = {
            WindowWithoutAutofill()
            val focus = remember { FocusRequester() }
            LaunchedEffect(focus) { focus.requestFocus() }
            // A name the dialog opens with is chosen whole, so typing replaces it.
            var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
                mutableStateOf(TextFieldValue(dialog.name, TextRange(0, dialog.name.length)))
            }
            // The text slot scrolls under the title and the buttons when the keyboard leaves it too little room.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                OutlinedTextField(
                    value = if (field.text == dialog.name) field else TextFieldValue(dialog.name, TextRange(dialog.name.length)),
                    onValueChange = {
                        field = it
                        onEvent(ProfileEvent.TypeName(it.text))
                    },
                    label = { Text(stringResource(R.string.profile_user_name)) },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Go,
                        ),
                    keyboardActions = KeyboardActions(onGo = { open() }),
                    modifier =
                        Modifier
                            .withoutAutofill()
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .testTag(ProfileTags.nameField),
                )
                if (dialog.recent.isNotEmpty()) {
                    Column {
                        Text(
                            stringResource(R.string.profile_recent),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            for (recent in dialog.recent) {
                                SuggestionChip(onClick = { onEvent(ProfileEvent.OpenProfile(recent)) }, label = { Text(recent) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open() }, enabled = name != null) { Text(stringResource(R.string.profile_open)) }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(ProfileEvent.DismissOpenProfile) }) { Text(stringResource(R.string.profile_cancel)) }
        },
        // At the window's side margin, as the edit sheet's dialogs are, and above the keyboard: an edge-to-edge
        // dialog window is not resized for it.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        modifier = Modifier.safeDrawingPadding().padding(horizontal = Spacing.xl),
    )
}
