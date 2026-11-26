package io.github.kauri_off.kiroku.list

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.ScreenScaffold
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.SessionBanner

internal enum class ListMode {
    Anime,
    Manga,
}

@Composable
internal fun ListFrame(
    sessionExpired: Boolean,
    onSignIn: () -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
    ime: WindowInsets? = null,
    topBar: @Composable () -> Unit,
    statusTabs: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    ScreenScaffold(
        modifier = modifier,
        topBar = {
            topBar()
            statusTabs()
            if (sessionExpired) SessionBanner(onSignIn = onSignIn)
        },
        snackbarHost = { ScreenSnackbarHost(snackbars, ime = ime) },
        content = content,
    )
}

@Composable
internal fun ListModeSwitch(
    mode: ListMode,
    onMode: (ListMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow {
        ListMode.entries.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = mode == choice,
                onClick = { if (mode != choice) onMode(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, ListMode.entries.size),
            ) {
                Text(stringResource(if (choice == ListMode.Anime) R.string.list_mode_anime else R.string.list_mode_manga))
            }
        }
    }
}
