package io.github.kauri_off.kiroku.shell

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kauri_off.kiroku.about.AboutKey
import io.github.kauri_off.kiroku.about.AboutRoute
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.link.Link
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.design.component.LocalMessageCovers
import io.github.kauri_off.kiroku.design.component.MessageCovers
import io.github.kauri_off.kiroku.link.AppLinks
import io.github.kauri_off.kiroku.list.ListMode
import io.github.kauri_off.kiroku.list.ListRoute
import io.github.kauri_off.kiroku.list.UserListRoute
import io.github.kauri_off.kiroku.manga.MangaTitleKey
import io.github.kauri_off.kiroku.manga.MangaTitleRoute
import io.github.kauri_off.kiroku.notifysettings.NotifySettingsKey
import io.github.kauri_off.kiroku.notifysettings.NotifySettingsRoute
import io.github.kauri_off.kiroku.profile.OpenedProfiles
import io.github.kauri_off.kiroku.profile.OtherProfileKey
import io.github.kauri_off.kiroku.profile.ProfileNotBuilt
import io.github.kauri_off.kiroku.profile.ProfileNotBuiltKey
import io.github.kauri_off.kiroku.profile.ProfileNotBuiltScreen
import io.github.kauri_off.kiroku.profile.ProfileRoute
import io.github.kauri_off.kiroku.search.BrowseKey
import io.github.kauri_off.kiroku.search.BrowseRoute
import io.github.kauri_off.kiroku.search.MangaRankingKey
import io.github.kauri_off.kiroku.search.MangaRankingRoute
import io.github.kauri_off.kiroku.search.MangaSearchKey
import io.github.kauri_off.kiroku.search.MangaSearchRoute
import io.github.kauri_off.kiroku.search.SearchRoute
import io.github.kauri_off.kiroku.search.searchStandIn
import io.github.kauri_off.kiroku.settings.SettingsKey
import io.github.kauri_off.kiroku.settings.SettingsRoute
import io.github.kauri_off.kiroku.signin.SignInKey
import io.github.kauri_off.kiroku.signin.SignInRoute
import io.github.kauri_off.kiroku.signin.SignInTurns
import io.github.kauri_off.kiroku.signin.browse
import io.github.kauri_off.kiroku.signin.signedIn
import io.github.kauri_off.kiroku.statistics.StatisticsRoute
import io.github.kauri_off.kiroku.statistics.statisticsPage
import io.github.kauri_off.kiroku.title.TitleKey
import io.github.kauri_off.kiroku.title.TitleLinks
import io.github.kauri_off.kiroku.title.TitleRoute
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.titlesub.DetailsKey
import io.github.kauri_off.kiroku.titlesub.DetailsRoute
import io.github.kauri_off.kiroku.titlesub.RecommendationsKey
import io.github.kauri_off.kiroku.titlesub.RecommendationsRoute
import io.github.kauri_off.kiroku.titlesub.RelatedKey
import io.github.kauri_off.kiroku.titlesub.RelatedRoute
import io.github.kauri_off.kiroku.titlesub.ScoresKey
import io.github.kauri_off.kiroku.titlesub.ScoresRoute
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.flow.first

// The app: the shell over the screens built so far, with the messages that outlive the screen that posted them.
@Composable
fun AppRoute(
    graph: AppGraph,
    navigator: ShellNavigator = rememberShellNavigator(),
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
    links: AppLinks? = null,
) {
    val messages = viewModel { ShellViewModel(graph.list) }.messages
    val profiles = viewModel { OpenedProfiles(graph.preferences) }
    val covers = remember { MessageCovers() }
    ShownMessages(messages, snackbars, covers)
    // "Change" opens the edit sheet on the List or on the title's own page; from elsewhere it goes to that page.
    val edit by messages.editRequest.collectAsScreenState()
    LaunchedEffect(edit) {
        edit?.let { request ->
            val owner = request.owner
            if (owner != null) {
                if (graph.session
                        .observeSession()
                        .first()
                        .owner != owner
                ) {
                    messages.editTaken(request)
                    return@let
                }
                val search = navigator.stackOf(Destination.Search)
                val origin = search.filterIsInstance<BrowseKey>().lastOrNull { it.list == BrowseList.Suggestions }
                if (navigator.current != Destination.Search) navigator.select(Destination.Search)
                if (origin?.owner != owner.value) {
                    val page = TitleKey(request.id.value, suggestionsOwner = owner.value)
                    if (navigator.stack.last() != page) navigator.open(page)
                    return@let
                }
            }
            navigator.showEditorOf(request.id)
        }
    }
    SignInTurns(graph, navigator)
    val session = SignOutTurns(graph, navigator, messages)
    val images = remember(graph, session) { session?.let(graph::imageLoaderFor) }
    if (links != null) LinkTurns(links, navigator)
    CompositionLocalProvider(LocalMessageCovers provides covers) {
        ShellRoute(navigator, snackbars, remember(graph, messages, profiles) { appEntries(graph, messages, profiles) }, images)
    }
}

private fun ShellNavigator.showEditorOf(id: AnimeId) {
    val stack = stackOf(visible.last())
    val page = TitleKey(id.value)
    val subScreen =
        when (val shown = stack.last()) {
            is DetailsKey -> shown.id == id.value
            is RelatedKey -> shown.id == id.value
            is ScoresKey -> shown.id == id.value
            is RecommendationsKey -> shown.id == id.value
            else -> false
        }
    when {
        (stack.last() is ListRoot && listMode == ListMode.Anime) || stack.last() == page -> Unit
        subScreen && stack.getOrNull(stack.lastIndex - 1) == page -> back()
        else -> open(page)
    }
}

// Each link handed over is acted on once.
@Composable
private fun LinkTurns(
    links: AppLinks,
    navigator: ShellNavigator,
) {
    val link by links.link.collectAsScreenState()
    LaunchedEffect(link) {
        link?.let {
            navigator.openLink(it.link)
            links.taken(it)
        }
    }
}

// A link opens its page over its destination's root with the List alone beneath (section 6.3), whatever was shown.
fun ShellNavigator.openLink(link: Link) {
    val page = linkedPage(link)
    home(Destination.List)
    home(page.destination)
    open(page.key)
}

// A title over the List; a profile over the own Profile.
fun linkedPage(link: Link): OpenedPage =
    when (link) {
        is Link.Title -> OpenedPage(Destination.List, TitleKey(link.id.value))
        is Link.MangaTitle -> OpenedPage(Destination.List, MangaTitleKey(link.id.value))
        is Link.Profile -> OpenedPage(Destination.Profile, OtherProfileKey(link.name))
    }

fun appEntries(
    graph: AppGraph,
    messages: ShellMessages,
    profiles: OpenedProfiles,
): ShellEntries {
    val standIn = searchStandIn(graph)
    return { navigator, snackbars ->
        val signIn = { navigator.open(SignInKey) }
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
                mode = navigator.listMode,
                onMode = { navigator.listMode = it },
                onOpenMangaTitle = navigator::open,
            )
        }
        entry<SearchRoot> {
            SearchRoute(
                standIn,
                graph,
                messages,
                snackbars,
                openTitle,
                onBrowse = { navigator.open(it) },
                onSignIn = signIn,
                onManga = { navigator.open(MangaSearchKey) },
            )
        }
        entry<MangaSearchKey> {
            MangaSearchRoute(
                graph,
                snackbars,
                onBack = { back() },
                onRanking = { navigator.open(MangaRankingKey) },
                onOpen = { navigator.open(MangaTitleKey(it)) },
                onSignIn = signIn,
            )
        }
        entry<MangaRankingKey> {
            MangaRankingRoute(
                graph,
                snackbars,
                onBack = { back() },
                onOpen = { navigator.open(MangaTitleKey(it)) },
                onSignIn = signIn,
            )
        }
        entry<BrowseKey> { key ->
            BrowseRoute(key, graph, messages, snackbars, onBack = { back() }, onOpenTitle = openTitle, onSignIn = signIn)
        }
        entry<ProfileRoot> { ProfileRoute(null, graph, messages, profiles, navigator, signIn, snackbars) }
        entry<OtherProfileKey> { key -> ProfileRoute(key, graph, messages, profiles, navigator, signIn, snackbars) }
        entry<ProfileNotBuiltKey> { key ->
            val name = key.name
            val page = key.statisticsPage
            when {
                key.screen == ProfileNotBuilt.UserList && name != null ->
                    UserListRoute(
                        name,
                        graph,
                        onRefreshFailed = { messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(it))) },
                        snackbars,
                        openTitle,
                        onBack = { back() },
                        onSignIn = signIn,
                    )
                page != null -> StatisticsRoute(page, key.name, graph, onBack = { back() }, onSignIn = signIn, snackbars)
                else -> ProfileNotBuiltScreen(key, onBack = { back() }, snackbars)
            }
        }
        entry<SettingsKey> {
            SettingsRoute(
                graph,
                messages,
                snackbars,
                onBack = { back() },
                onNotifications = { navigator.open(NotifySettingsKey) },
                onAbout = { navigator.open(AboutKey) },
            )
        }
        entry<NotifySettingsKey> {
            NotifySettingsRoute(graph, messages, snackbars, onBack = { back() }, onOpenTitle = { openTitle(it, null) }, onSignIn = signIn)
        }
        entry<AboutKey> { AboutRoute(snackbars, onBack = { back() }) }
        entry<MangaTitleKey> { key -> MangaTitleRoute(key, graph, messages, snackbars, onBack = { back() }, onSignIn = signIn) }
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
                    scores = { navigator.open(ScoresKey(it.value)) },
                    recommendations = { id, opened -> navigator.open(RecommendationsKey(id.value, opened)) },
                    signIn = signIn,
                ),
            )
        }
        entry<RelatedKey> { key -> RelatedRoute(key, graph, snackbars, onBack = { back() }, onOpenTitle = openTitle, onSignIn = signIn) }
        entry<DetailsKey> { key -> DetailsRoute(key, graph, snackbars, onBack = { back() }, onSignIn = signIn) }
        entry<ScoresKey> { key -> ScoresRoute(key, graph, snackbars, onBack = { back() }, onSignIn = signIn) }
        entry<RecommendationsKey> { key ->
            RecommendationsRoute(key, graph, messages, snackbars, onBack = { back() }, onOpenTitle = openTitle, onSignIn = signIn)
        }
        entry<SignInKey> {
            SignInRoute(graph, snackbars, onBack = { back() }, onBrowse = { navigator.browse() }, onSignedIn = { navigator.signedIn() })
        }
    }
}
