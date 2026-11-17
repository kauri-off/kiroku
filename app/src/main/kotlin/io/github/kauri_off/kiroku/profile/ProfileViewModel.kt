package io.github.kauri_off.kiroku.profile

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.model.UserStatistics
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserRepository
import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.core.user.UserListProgress
import io.github.kauri_off.kiroku.core.user.UserListState
import io.github.kauri_off.kiroku.core.user.ofUser
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.readAgainOnRetry
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.stale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

// One user's Profile: the signed-in user's from the stored profile and list, another's from the read of their list.
// Navigation is the route's.
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel(
    private val owner: ProfileOwner,
    session: SessionRepository,
    private val list: ListSource,
    private val user: UserRepository,
    connection: Connection,
    private val clock: Clock,
    background: CoroutineDispatcher,
    private val saved: SavedStateHandle,
    private val messages: ShellMessages,
    private val opened: OpenedProfiles,
) : ViewModel() {
    private class Built(
        val content: ProfileContent,
        val reading: UserListProgress? = null,
    )

    // Whose profile another user's is: the own one, someone else's, or not known until the own profile is read.
    private enum class Whose { Own, Other, Unknown }

    // What the ask for the own profile left stored, once it ended.
    private class Fetched(
        val profile: UserProfile?,
    )

    private val sessions = session.observeSession()
    private val typed = mutableStateOf(saved.get<String>(TYPED))
    private val retries = MutableStateFlow(0)
    private val pulling = MutableStateFlow(false)
    private val shownOnce = MutableStateFlow(false)
    private val isOwn = MutableStateFlow(owner == ProfileOwner.Me)
    private val fetchedOwn = MutableStateFlow<Fetched?>(null)
    private var load: Job? = null
    private var learning: Job? = null

    // The signed-in user's name as last read, to tell the own name from another.
    @Volatile private var ownName: String? = null

    // The list the statistics shown were computed over: a list held is the same object in every state that holds it.
    private var counted: Pair<List<ListEntry>, UserStatistics>? = null

    // A header that cannot be read is left out, as a secondary part is.
    private val ownProfile: Flow<UserProfile?> =
        user
            .observeOwnProfile()
            .catch { if (it is DataException) emit(null) else throw it }

    // The own profile as the session has it: none signed out, whatever the store still holds.
    private val signedInProfile: Flow<Pair<Session, UserProfile?>> =
        combine(sessions, ownProfile) { session, profile -> session to profile.takeUnless { session == Session.SignedOut } }
            .onEach { ownName = it.second?.name }

    private val built: Flow<Built> =
        when (owner) {
            ProfileOwner.Me ->
                signedInProfile
                    .map { it.first == Session.SignedOut }
                    .distinctUntilChanged()
                    .flatMapLatest { signedOut -> if (signedOut) flowOf(Built(ProfileContent.SignedOut)) else ownContent() }
            is ProfileOwner.Other ->
                combine(signedInProfile, fetchedOwn) { (session, stored), fetched ->
                    val profile = stored ?: fetched?.profile
                    when {
                        profile != null -> if (user.sameUser(profile.name, owner.name)) Whose.Own else Whose.Other
                        session == Session.SignedOut || fetched != null -> Whose.Other
                        else -> Whose.Unknown
                    }
                }.distinctUntilChanged()
                    .flatMapLatest { whose ->
                        isOwn.value = whose == Whose.Own
                        when (whose) {
                            Whose.Own -> ownContent()
                            Whose.Other -> otherContent(owner.name)
                            Whose.Unknown -> flowOf(Built(ProfileContent.Loading))
                        }
                    }
        }

    val state: StateFlow<ProfileState> =
        combine(built, sessions, pulling) { built, session, pulling ->
            ProfileState(
                owner = owner,
                content = built.content,
                sessionExpired = session is Session.Expired,
                refreshing = pulling || (built.reading != null && built.content is ProfileContent.Shown),
                reading = built.reading,
            )
        }.flowOn(background)
            .stateIn(viewModelScope, WhileShown, ProfileState(owner, ProfileContent.Loading))

    // Another user's profile under the signed-in user's own name: the route shows the own Profile in its place.
    val own: StateFlow<Boolean> = isOwn.asStateFlow()

    private val loads =
        ShownLoads(
            viewModelScope,
            connection,
            whenShown = {
                shownOnce.value = true
                if (isOwn.value) refresh(Asked.Shown) else learnOwn()
            },
            again = { refresh(Asked.Shown) },
        )

    // Collected while the Profile is shown; nothing is read for another user before then.
    val shown: StateFlow<Unit> get() = loads.shown

    // The dialog's field, held as Compose state so the field reads each keystroke in the same frame, with [recent].
    fun openProfile(recent: List<String>): OpenProfile? = typed.value?.let { OpenProfile(it, recent) }

    // Whether [name] is the signed-in user's, as far as the own profile was read.
    fun isOwnName(name: String): Boolean = ownName?.let { user.sameUser(it, name) } ?: false

    fun onEvent(event: ProfileEvent) {
        when (event) {
            ProfileEvent.Retry -> {
                retries.update { it + 1 }
                if (!isOwn.value) refresh(Asked.Retry)
            }
            ProfileEvent.Refresh -> refresh(Asked.Pull)
            is ProfileEvent.StartOpenProfile -> type(event.name)
            is ProfileEvent.TypeName -> type(event.text)
            is ProfileEvent.OpenProfile, ProfileEvent.DismissOpenProfile -> type(null)
            else -> Unit
        }
    }

    private fun type(text: String?) {
        typed.value = text
        saved[TYPED] = text
    }

    // The statistics follow the stored list, so an edit changes them at once and they show offline.
    private fun ownContent(): Flow<Built> =
        combine(ownProfile, list.observeEntries()) { profile, entries ->
            Built(ProfileContent.Shown(profile, statistics(entries)))
        }.readAgainOnRetry(retries) { Built(ProfileContent.Failed(it)) }

    // Signed in with no own profile stored, the own profile is read before another user's list, so the own name is
    // never read as someone else's.
    private fun learnOwn() {
        if (owner !is ProfileOwner.Other || fetchedOwn.value != null || learning?.isActive == true) return
        learning =
            viewModelScope.launch {
                if (sessions.first() == Session.SignedOut) {
                    fetchedOwn.value = Fetched(null)
                    return@launch
                }
                val stored = storedProfile()
                if (stored == null) user.refreshOwnProfile()
                fetchedOwn.value = Fetched(stored ?: storedProfile())
            }
    }

    // Asks for the list only once the screen was shown; a list not read whole yet shows how far it is, never statistics.
    // The end of a read again is the refresh's to report, so a failed pull stays owed whatever arrives first.
    private fun otherContent(name: String): Flow<Built> =
        flow {
            emit(Built(ProfileContent.Loading))
            shownOnce.first { it }
            var readAgain = false
            user.observeList(name).collect { list ->
                if (list.isOfUser()) opened.opened(name, user::sameUser)
                when (list) {
                    is UserListState.None -> loads.ended(list.error)
                    is UserListState.Whole -> if (list.again == null && !readAgain) loads.ended(null)
                    is UserListState.Reading -> Unit
                }
                readAgain = list is UserListState.Whole && list.again != null
                emit(built(list))
            }
        }

    // The read showed that the name is a user's.
    private fun UserListState.isOfUser(): Boolean =
        when (this) {
            is UserListState.Reading -> entries.isNotEmpty()
            is UserListState.Whole -> true
            is UserListState.None -> error == DataError.PrivateList
        }

    private fun built(list: UserListState): Built =
        when (list) {
            is UserListState.Reading -> Built(ProfileContent.ListLoading, UserListProgress(list.entries.size, list.total))
            is UserListState.Whole -> Built(ProfileContent.Shown(list.user, statistics(list.entries)), list.again)
            is UserListState.None -> Built(ProfileContent.Failed(list.error))
        }

    private fun statistics(entries: List<ListEntry>): UserStatistics =
        counted?.takeIf { it.first === entries }?.second ?: statisticsOf(entries).also { counted = entries to it }

    // A refresh with content shown that fails is a message; a Retry fails where it was tapped (section 7.2).
    private fun refresh(asked: Asked) {
        if (load?.isActive == true) return
        val held = state.value.content is ProfileContent.Shown
        load =
            viewModelScope.launch {
                pulling.value = asked == Asked.Pull
                loads.started()
                val error = if (isOwn.value) refreshOwn(asked) else refreshList()
                pulling.value = false
                loads.ended(error)
                if (error != null && held && asked != Asked.Retry && !error.ofUser && error != DataError.SessionExpired) {
                    messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(error)))
                }
            }
    }

    // Shown, the list is downloaded when stale and the profile read with it, or when none is stored; a pull asks both.
    private suspend fun refreshOwn(asked: Asked): DataError? {
        if (sessions.first() == Session.SignedOut) return null
        val stale = asked != Asked.Shown || list.stale(clock.instant())
        val unknown = !stale && storedProfile() == null
        return coroutineScope {
            val synced = async { if (stale) list.sync() else Outcome.Success(Unit) }
            val profile = async { if (stale || unknown) user.refreshOwnProfile() else Outcome.Success(Unit) }
            listOf(synced.await(), profile.await()).firstNotNullOfOrNull { (it as? Outcome.Failure)?.error }
        }
    }

    // A pull, a Retry, or the connection back: the list is read again, or the read under way is joined.
    private suspend fun refreshList(): DataError? {
        val name = (owner as? ProfileOwner.Other)?.name ?: return null
        return (user.refreshList(name) as? Outcome.Failure)?.error
    }

    private suspend fun storedProfile(): UserProfile? =
        try {
            user.observeOwnProfile().first()
        } catch (_: DataException) {
            null
        }

    private enum class Asked { Shown, Retry, Pull }

    private companion object {
        val TYPED = notShown("profileTyped")
    }
}
