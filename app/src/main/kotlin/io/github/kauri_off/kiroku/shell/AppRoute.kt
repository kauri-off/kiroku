package io.github.kauri_off.kiroku.shell

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.about.AboutKey
import io.github.kauri_off.kiroku.about.AboutRoute
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.link.Link
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.RecentTitleId
import io.github.kauri_off.kiroku.design.component.LocalMessageCovers
import io.github.kauri_off.kiroku.design.component.MessageCovers
import io.github.kauri_off.kiroku.edit.MangaEditSheet
import io.github.kauri_off.kiroku.link.AppLinks
import io.github.kauri_off.kiroku.list.ListMode
import io.github.kauri_off.kiroku.list.ListRoute
import io.github.kauri_off.kiroku.list.UserListRoute
import io.github.kauri_off.kiroku.manga.LocalMangaEditOrigin
import io.github.kauri_off.kiroku.manga.LocalMangaEditing
import io.github.kauri_off.kiroku.manga.MangaEditOrigin
import io.github.kauri_off.kiroku.manga.MangaTitleKey
import io.github.kauri_off.kiroku.manga.MangaTitleRoute
import io.github.kauri_off.kiroku.manga.MangaTitleSummary
import io.github.kauri_off.kiroku.manga.PublicMangaListKey
import io.github.kauri_off.kiroku.manga.PublicMangaListRoute
import io.github.kauri_off.kiroku.notShown
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
import io.github.kauri_off.kiroku.search.RecentTitleOpening
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
import kotlinx.coroutines.flow.firstOrNull

// The app: the shell over the screens built so far, with the messages that outlive the screen that posted them.
@Composable
fun AppRoute(
    graph: AppGraph,
    navigator: ShellNavigator = rememberShellNavigator(),
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
    links: AppLinks? = null,
) {
    val shell = viewModel { ShellViewModel(graph.list, graph.mangaListEditor, graph.session.observeSession()) }
    val messages = shell.messages
    val profiles = viewModel { OpenedProfiles(graph.preferences) }
    val covers = remember { MessageCovers() }
    ShownMessages(messages, snackbars, covers)
    // "Change" opens the edit sheet on the List or on the title's own page; from elsewhere it goes to that page.
    val edit by messages.editRequest.collectAsScreenState()
    LaunchedEffect(edit) {
        edit?.let { request ->
            val owner = request.owner
            val active = graph.session.observeSession().first()
            val recentScope =
                graph.recentTitles
                    .observeScope()
                    .firstOrNull()
                    ?.takeIf { it.owner == active.owner }
            if (owner != null) {
                if (active.owner != owner) {
                    messages.editTaken(request)
                    return@let
                }
                val search = navigator.stackOf(Destination.Search)
                val origin = search.filterIsInstance<BrowseKey>().lastOrNull { it.list == BrowseList.Suggestions }
                if (navigator.current != Destination.Search) navigator.select(Destination.Search)
                if (origin?.owner != owner.value) {
                    val page = TitleKey(request.id.value, suggestionsOwner = owner.value)
                    val shown = navigator.stack.last() as? TitleKey
                    if (shown?.id != request.id.value || shown.suggestionsOwner != owner.value) navigator.open(page)
                    return@let
                }
            }
            navigator.showEditorOf(request.id, graph.mangaList != null, active.owner, recentScope?.key)
        }
    }
    SignInTurns(graph, navigator)
    val session = SignOutTurns(graph, navigator, messages)
    val images = remember(graph, session) { session?.let(graph::imageLoaderFor) }
    val mangaEditing = shell.mangaEditing
    val mangaEdit by messages.mangaEditRequest.collectAsScreenState()
    LaunchedEffect(mangaEdit) {
        val request = mangaEdit ?: return@LaunchedEffect
        messages.editTaken(request)
        if (graph.session.observeSession().first() != Session.SignedIn(request.owner)) return@LaunchedEffect
        val editing = mangaEditing?.state?.value ?: return@LaunchedEffect
        if (editing.owner != request.owner || !editing.signedIn || editing.open != null) return@LaunchedEffect
        if (editing.snapshot?.entries?.any { it.id == request.id } != true) return@LaunchedEffect
        val shown = navigator.stack.last() as? MangaTitleKey
        val same = shown?.id == request.id.value && (shown.recentOwner == null || shown.recentOwner == request.owner.value)
        if (!same && !navigator.mangaEditOrigin().inMangaList) navigator.open(MangaTitleKey(request.id.value))
        mangaEditing.open(request.owner, request.id, request.title, navigator.mangaEditOrigin(), request.totals)
    }
    if (links != null) LinkTurns(links, navigator) { mangaEditing?.dismissPresentation() }
    val origin = navigator.mangaEditOrigin()
    CompositionLocalProvider(
        LocalMessageCovers provides covers,
        LocalMangaEditing provides mangaEditing,
        LocalMangaEditOrigin provides origin,
    ) {
        ShellRoute(navigator, snackbars, remember(graph, messages, profiles) { appEntries(graph, messages, profiles) }, images)
        if (mangaEditing != null) {
            val editing by mangaEditing.state.collectAsScreenState()
            val presentation = editing.open
            LaunchedEffect(presentation?.instance, origin) {
                if (presentation != null && presentation.origin != origin) mangaEditing.dismissPresentation(presentation.instance)
            }
            val open = presentation?.takeIf { session == Session.SignedIn(it.owner) && it.origin == origin }
            if (open != null) {
                key(open.instance) {
                    MangaEditSheet(open.form) { if (open.origin == navigator.mangaEditOrigin()) mangaEditing.event(open, it) }
                }
            }
        }
    }
}

private fun ShellNavigator.mangaEditOrigin(): MangaEditOrigin {
    val page = stack.last()
    return MangaEditOrigin(current, page, restarts, page == ListRoot && listMode == ListMode.Manga)
}

private fun ShellNavigator.showEditorOf(
    id: AnimeId,
    mangaAvailable: Boolean,
    owner: SignInId?,
    recentScope: String?,
) {
    val stack = stackOf(visible.last())
    val subScreen =
        when (val shown = stack.last()) {
            is DetailsKey -> shown.id == id.value
            is RelatedKey -> shown.id == id.value
            is ScoresKey -> shown.id == id.value
            is RecommendationsKey -> shown.id == id.value
            else -> false
        }
    when {
        (stack.last() is ListRoot && (!mangaAvailable || listMode == ListMode.Anime)) -> Unit
        (stack.last() as? TitleKey).matches(id, owner, recentScope) -> Unit
        subScreen && (stack.getOrNull(stack.lastIndex - 1) as? TitleKey).matches(id, owner, recentScope) -> back()
        else -> open(TitleKey(id.value))
    }
}

private fun TitleKey?.matches(
    id: AnimeId,
    owner: SignInId?,
    recentScope: String?,
): Boolean =
    this != null &&
        this.id == id.value &&
        (suggestionsOwner == null || suggestionsOwner == owner?.value) &&
        (recentOwner == null || recentOwner == recentScope)

// Each link handed over is acted on once.
@Composable
private fun LinkTurns(
    links: AppLinks,
    navigator: ShellNavigator,
    beforeOpening: () -> Unit,
) {
    val link by links.link.collectAsScreenState()
    LaunchedEffect(link) {
        link?.takeIf { it == links.link.value }?.let {
            beforeOpening()
            navigator.openLink(it.link)
            links.taken(it)
        }
    }
}

// A link opens its page over its destination's root with the List alone beneath (section 6.3), whatever was shown.
fun ShellNavigator.openLink(link: Link) = openLinkedPage(linkedPage(link))

internal fun ShellNavigator.openLinkedPage(page: OpenedPage) {
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
                onOpenRecent = { navigator.open(it.page()) },
            )
        }
        entry<MangaSearchKey> {
            MangaSearchRoute(
                graph,
                messages,
                snackbars,
                onBack = { back() },
                onRanking = { navigator.open(MangaRankingKey) },
                onOpenRecent = { navigator.open(it.page()) },
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
        entry<PublicMangaListKey> { key ->
            val lists = graph.publicMangaLists
            if (lists != null) {
                PublicMangaListRoute(
                    key,
                    lists,
                    graph,
                    onRefreshFailed = { messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(it))) },
                    snackbars,
                    onOpen = { navigator.open(it) },
                    onBack = { back() },
                    onSignIn = signIn,
                )
            } else {
                LaunchedEffect(key) { navigator.remove(key) }
            }
        }
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

private fun RecentTitleOpening.page(): NavKey =
    when (val key = title.key) {
        is RecentTitleId.Anime -> {
            val anime =
                Anime(
                    id = AnimeId(key.value),
                    titles = title.titles,
                    posterUrl = null,
                    largePosterUrl = null,
                    mediaType = null,
                    airingStatus = AiringStatus.Unrecognized(notShown("")),
                    malEpisodes = null,
                    startDate = null,
                    broadcast = null,
                    meanScore = null,
                    rank = null,
                    genres = null,
                    averageEpisodeDuration = null,
                    safeForSearch = title.safeForSearch,
                )
            TitleKey(key.value, TitleSummary(anime, null, null, session, language = language), recentOwner = scope.key)
        }
        is RecentTitleId.Manga -> {
            val manga =
                MangaDetails(
                    id = MangaId(key.value),
                    titles = title.titles,
                    posterUrl = null,
                    largePosterUrl = null,
                    synopsis = null,
                    format = null,
                    publication = null,
                    volumes = null,
                    chapters = null,
                    meanScore = null,
                    safeForSearch = title.safeForSearch,
                )
            MangaTitleKey(MangaTitleSummary(manga, language, session.owner), recentOwner = scope.key)
        }
    }
