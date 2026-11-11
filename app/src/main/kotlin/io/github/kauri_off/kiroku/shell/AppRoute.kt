package io.github.kauri_off.kiroku.shell

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.list.ListRoute
import io.github.kauri_off.kiroku.title.TitleKey
import io.github.kauri_off.kiroku.title.TitleLinks
import io.github.kauri_off.kiroku.title.TitleRoute
import io.github.kauri_off.kiroku.titlesub.DetailsKey
import io.github.kauri_off.kiroku.titlesub.DetailsRoute
import io.github.kauri_off.kiroku.titlesub.RelatedKey
import io.github.kauri_off.kiroku.titlesub.RelatedRoute
import io.github.kauri_off.kiroku.wiring.AppGraph

// The app: the shell over the screens built so far, and the list's write failures (W2) on the shell's snackbar host,
// which outlives the screen that made the edit.
@Composable
fun AppRoute(
    graph: AppGraph,
    navigator: ShellNavigator = rememberShellNavigator(),
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
) {
    WriteFailures(graph.list, snackbars)
    ShellRoute(navigator, snackbars, remember(graph) { appEntries(graph) })
}

fun appEntries(graph: AppGraph): ShellEntries =
    { navigator, snackbars ->
        val signIn = { navigator.open(NotBuiltKey(NotBuilt.SignIn)) }
        val openTitle = { id: AnimeId -> navigator.open(TitleKey(id.value)) }
        val back = { navigator.back() }
        entry<ListRoot> {
            ListRoute(graph, snackbars, onOpenTitle = openTitle, onSearch = { navigator.select(Destination.Search) }, onSignIn = signIn)
        }
        entry<SearchRoot> { NotBuiltDestination(graph, Destination.Search, signIn, snackbars) }
        entry<ProfileRoot> { NotBuiltDestination(graph, Destination.Profile, signIn, snackbars) }
        entry<TitleKey> { key ->
            TitleRoute(
                key,
                graph,
                snackbars,
                TitleLinks(
                    back = { back() },
                    openTitle = openTitle,
                    seeAllRelated = { navigator.open(RelatedKey(it.value)) },
                    details = { navigator.open(DetailsKey(it.value)) },
                    recommendations = { navigator.open(NotBuiltKey(NotBuilt.Recommendations)) },
                    signIn = signIn,
                ),
            )
        }
        entry<RelatedKey> { key -> RelatedRoute(key, graph, snackbars, onBack = { back() }, onOpenTitle = openTitle, onSignIn = signIn) }
        entry<DetailsKey> { key -> DetailsRoute(key, graph, snackbars, onBack = { back() }, onSignIn = signIn) }
        entry<NotBuiltKey> { key -> NotBuiltScreen(key.screen, onBack = { back() }, snackbars) }
    }

// Search and Profile until their milestones: the placeholder, with the session banner of S6.
@Composable
private fun NotBuiltDestination(
    graph: AppGraph,
    destination: Destination,
    onSignIn: () -> Unit,
    snackbars: SnackbarHostState,
) {
    val session by remember(graph) { graph.session.observeSession() }.collectAsState(initial = null)
    PlaceholderScreen(PlaceholderState(destination, sessionExpired = session == Session.Expired), { onSignIn() }, snackbars)
}

// W2's "Could not save · Retry"; Retry applies, as a new edit, the values shown just before the failure.
@Composable
private fun WriteFailures(
    list: ListRepository,
    snackbars: SnackbarHostState,
) {
    val message = stringResource(R.string.shell_could_not_save)
    val retry = stringResource(R.string.design_retry)
    LaunchedEffect(list, message, retry) {
        list.observeWriteFailures().collect { failure ->
            val result = snackbars.showSnackbar(message, actionLabel = retry, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) list.write(failure.retry)
        }
    }
}
