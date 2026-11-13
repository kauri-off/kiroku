package io.github.kauri_off.kiroku.shell

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.design.component.LocalMessageCovers
import io.github.kauri_off.kiroku.design.component.MessageCovers
import io.github.kauri_off.kiroku.list.ListRoute
import io.github.kauri_off.kiroku.profile.OtherProfileKey
import io.github.kauri_off.kiroku.profile.ProfileNotBuiltKey
import io.github.kauri_off.kiroku.profile.ProfileNotBuiltScreen
import io.github.kauri_off.kiroku.profile.ProfileOwner
import io.github.kauri_off.kiroku.profile.ProfileStandInRoute
import io.github.kauri_off.kiroku.profile.profileStandIn
import io.github.kauri_off.kiroku.title.TitleKey
import io.github.kauri_off.kiroku.title.TitleLinks
import io.github.kauri_off.kiroku.title.TitleRoute
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.titlesub.DetailsKey
import io.github.kauri_off.kiroku.titlesub.DetailsRoute
import io.github.kauri_off.kiroku.titlesub.RelatedKey
import io.github.kauri_off.kiroku.titlesub.RelatedRoute
import io.github.kauri_off.kiroku.wiring.AppGraph

// The app: the shell over the screens built so far, with the messages that outlive the screen that posted them.
@Composable
fun AppRoute(
    graph: AppGraph,
    navigator: ShellNavigator = rememberShellNavigator(),
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
) {
    val messages = viewModel { ShellViewModel(graph.list) }.messages
    val covers = remember { MessageCovers() }
    ShownMessages(messages, snackbars, covers)
    // "Change" opens the edit sheet on the List or on the title's own page; from elsewhere it goes to that page.
    val edit by messages.editRequest.collectAsScreenState()
    LaunchedEffect(edit) {
        edit?.let { navigator.showEditorOf(it) }
    }
    CompositionLocalProvider(LocalMessageCovers provides covers) {
        ShellRoute(navigator, snackbars, remember(graph, messages) { appEntries(graph, messages) })
    }
}

private fun ShellNavigator.showEditorOf(id: AnimeId) {
    val stack = stackOf(visible.last())
    val page = TitleKey(id.value)
    val subScreen = stack.last().let { it == DetailsKey(id.value) || it == RelatedKey(id.value) }
    when {
        stack.last() is ListRoot || stack.last() == page -> Unit
        subScreen && stack.getOrNull(stack.lastIndex - 1) == page -> back()
        else -> open(page)
    }
}

fun appEntries(
    graph: AppGraph,
    messages: ShellMessages,
): ShellEntries =
    { navigator, snackbars ->
        val signIn = { navigator.open(NotBuiltKey(NotBuilt.SignIn)) }
        val openTitle = { id: AnimeId, opened: TitleSummary? -> navigator.open(TitleKey(id.value, opened)) }
        val back = { navigator.back() }
        entry<ListRoot> {
            ListRoute(
                graph,
                messages,
                snackbars,
                onOpenTitle = openTitle,
                onSearch = { navigator.select(Destination.Search) },
                onSignIn = signIn,
            )
        }
        entry<SearchRoot> { NotBuiltDestination(graph, Destination.Search, signIn, snackbars) }
        entry<ProfileRoot> {
            val standIn = profileStandIn
            if (standIn == null) {
                NotBuiltDestination(graph, Destination.Profile, signIn, snackbars)
            } else {
                ProfileStandInRoute(standIn, ProfileOwner.Me, graph, navigator, signIn, snackbars)
            }
        }
        entry<OtherProfileKey> { key ->
            val standIn = profileStandIn
            if (standIn == null) {
                NotBuiltDestination(graph, Destination.Profile, signIn, snackbars)
            } else {
                ProfileStandInRoute(standIn, ProfileOwner.Other(key.name), graph, navigator, signIn, snackbars)
            }
        }
        entry<ProfileNotBuiltKey> { key -> ProfileNotBuiltScreen(key, onBack = { back() }, snackbars) }
        entry<TitleKey> { key ->
            TitleRoute(
                key,
                graph,
                messages,
                snackbars,
                TitleLinks(
                    back = { back() },
                    openTitle = openTitle,
                    seeAllRelated = { id, opened -> navigator.open(RelatedKey(id.value, opened)) },
                    details = { id, opened -> navigator.open(DetailsKey(id.value, opened)) },
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
    val session by remember(graph) { graph.session.observeSession() }.collectAsScreenState(initial = null)
    PlaceholderScreen(PlaceholderState(destination, sessionExpired = session == Session.Expired), { onSignIn() }, snackbars)
}
