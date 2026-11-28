package io.github.kauri_off.kiroku.manga

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.CountAsShown
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.RecentTitle
import io.github.kauri_off.kiroku.core.repository.RecentTitleId
import io.github.kauri_off.kiroku.edit.MangaEditTotals
import io.github.kauri_off.kiroku.search.RecordTitleVisit
import io.github.kauri_off.kiroku.search.recentOpeningIsCurrent
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
internal data class MangaTitleKey(
    val id: Long,
    val recentOwner: String? = null,
) : NavKey {
    @Transient
    var opened: MangaTitleSummary? = null
        private set

    constructor(opened: MangaTitleSummary, recentOwner: String? = null) : this(opened.details.id.value, recentOwner) {
        this.opened = opened
    }
}

internal data class MangaTitleSummary(
    val details: MangaDetails,
    val language: TitleLanguage,
    val owner: SignInId? = null,
)

@Composable
internal fun MangaTitleRoute(
    key: MangaTitleKey,
    graph: AppGraph,
    messages: ShellMessages,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    if (!recentOpeningIsCurrent(graph, key.recentOwner)) return
    val manga =
        viewModel {
            MangaTitleViewModel(
                MangaId(key.id),
                graph.manga,
                graph.clock,
                graph.connection,
                graph.titleLanguage,
                graph.session.observeSession(),
                onRefreshFailed = { messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(it))) },
                opened = key.opened,
            )
        }
    val state by manga.state.collectAsScreenState()
    var coverOpen by rememberSaveable(key.id) { mutableStateOf(false) }
    CountAsShown(manga.shown)
    val editor = LocalMangaEditing.current
    val origin = LocalMangaEditOrigin.current
    LaunchedEffect(editor) { editor?.watch() }
    val editing =
        editor?.state?.collectAsScreenState()?.value?.takeIf {
            it.owner != null && (key.opened?.owner == null || key.opened?.owner == it.owner)
        }
    val owner = editing?.owner
    val content = state.content as? MangaTitleContent.Shown
    RecordTitleVisit(
        graph,
        state.session,
        content?.details?.let { RecentTitle(RecentTitleId.Manga(key.id), it.titles, it.safeForSearch) },
    )
    val membership =
        if (editor != null && owner != null && content != null && editing.signedIn && editing.observed) {
            key(editor, owner, key.id) {
                val scope = rememberCoroutineScope()
                remember(scope) { MangaMembership(scope, graph.connection, editor, owner) }.also { CountAsShown(it.shown) }
            }
        } else {
            null
        }
    val membershipError = membership?.error?.collectAsScreenState()?.value
    val checking = membership?.checking?.collectAsScreenState()?.value == true
    val id = MangaId(key.id)
    val reconciling = membership != null && !state.sessionExpired && editing?.reconcilingAdd?.contains(id) == true
    val addAction =
        when {
            membership == null || editing == null || state.sessionExpired -> null
            reconciling -> null
            id in editing.adding -> MangaAddAction.Saving
            !checking && membershipError == null && editing.canAdd(id) -> MangaAddAction.Available
            else -> null
        }
    val entry =
        editing
            ?.snapshot
            ?.entries
            ?.find { it.id.value == key.id }
            ?.let(editing::displayed)
    MangaTitleScreen(
        state.copy(
            listEntry = entry.takeIf { editing?.signedIn == true && addAction == null },
            membershipError = membershipError,
            addAction = addAction,
            reconcilingAdd = reconciling,
            checkingMembership = checking || (reconciling && id in editing.adding),
            coverOpen = coverOpen,
        ),
        onEvent = {
            when (it) {
                MangaTitleEvent.Back -> onBack()
                MangaTitleEvent.OpenCover -> coverOpen = true
                MangaTitleEvent.CloseCover -> coverOpen = false
                MangaTitleEvent.SignIn -> onSignIn()
                MangaTitleEvent.RetryMembership -> {
                    if (reconciling && owner != null) editor.refreshAdd(owner, id) else membership?.request(force = true)
                }
                MangaTitleEvent.AddToList ->
                    if (addAction == MangaAddAction.Available &&
                        membership?.checking?.value == false &&
                        membership.error.value == null &&
                        owner != null &&
                        content != null
                    ) {
                        editor.addPlanned(owner, id, content.title, MangaEditTotals(content.details.chapters, content.details.volumes))
                    }
                MangaTitleEvent.Edit ->
                    if (entry != null &&
                        owner != null &&
                        content != null
                    ) {
                        editor.open(
                            owner,
                            entry.id,
                            content.title,
                            origin,
                            MangaEditTotals(content.details.chapters, content.details.volumes),
                        )
                    }
                MangaTitleEvent.Retry, MangaTitleEvent.Refresh -> {
                    membership?.request(force = true)
                    manga.refresh()
                }
            }
        },
        snackbars,
    )
}

internal class MangaMembership(
    private val scope: CoroutineScope,
    connection: Connection,
    private val editor: MangaEditing,
    private val owner: SignInId,
) {
    val error = MutableStateFlow<DataError?>(null)
    val checking = MutableStateFlow(false)
    private var pending = false
    private var covered = false
    private var load: Job? = null
    private val loads =
        ShownLoads(
            scope,
            connection,
            whenShown = { request() },
            again = { request(force = true) },
            shownAgain = { request() },
            hidden = { covered = true },
            trackOutagesWhileShown = true,
        )
    val shown = loads.shown

    fun request(force: Boolean = false) {
        pending = pending || force
        if (!loads.isShowing || load?.isActive == true) return
        checking.value = true
        load =
            scope.launch(start = CoroutineStart.LAZY) {
                val explicit = pending
                pending = false
                covered = false
                val result = editor.loadMembership(owner, explicit) { loads.isShowing }
                load = null
                if (result == null) {
                    checking.value = false
                    pending = pending || (explicit && (covered || !loads.isShowing))
                    if (pending && loads.isShowing) request()
                    return@launch
                }
                error.value = (result as? Outcome.Failure)?.error?.takeUnless { it == DataError.ListEnded }
                checking.value = false
                loads.ended(error.value, retryAfterHiddenFailure = covered || !loads.isShowing)
                if (pending) request()
            }
        load?.start()
    }
}
