package io.github.kauri_off.kiroku.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.ScreenState

// Stands in for a destination's screen until that screen is built.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(destination: Destination) {
    val title =
        when (destination) {
            Destination.List -> R.string.shell_my_list
            Destination.Search -> R.string.shell_search
            Destination.Profile -> R.string.shell_profile
        }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(title)) })
                SessionBannerPlace()
            }
        },
    ) { padding ->
        ScreenState(ScreenState.Empty(stringResource(R.string.shell_not_in_this_build)), Modifier.padding(padding))
    }
}
