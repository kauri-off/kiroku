package io.github.kauri_off.kiroku.manga

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import io.github.kauri_off.kiroku.list.ListPane
import io.github.kauri_off.kiroku.list.ListScreen

@Composable
internal fun PublicMangaListScreen(
    name: String,
    state: MangaListState,
    snackbars: SnackbarHostState,
    onEvent: (MangaListEvent) -> Unit,
) = ListScreen(ListPane.Manga(state, onEvent, publicName = name), snackbars)
