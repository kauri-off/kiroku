package io.github.kauri_off.kiroku.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.shell.NotBuiltScreen
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.serialization.Serializable

// What the Profile shows until its data layer exists: fixture states in debug and preview builds, none in release.
internal interface ProfileStandIn {
    val own: ProfileContent

    fun other(name: String): ProfileContent

    // The names opened last that turned out to exist, newest first.
    val recent: List<String>

    fun opened(name: String)
}

@Serializable
data class OtherProfileKey(
    val name: String,
) : NavKey

// A screen the Profile leads to that a later task builds.
enum class ProfileNotBuilt { Settings, ScoreDistribution, Genres, FormatsAndYears, UserList }

@Serializable
data class ProfileNotBuiltKey(
    val screen: ProfileNotBuilt,
    val name: String? = null,
) : NavKey

// The Profile on its stand-in, with the session of the app; replaced by a view model's route with the data layer.
@Composable
internal fun ProfileStandInRoute(
    standIn: ProfileStandIn,
    owner: ProfileOwner,
    graph: AppGraph,
    navigator: ShellNavigator,
    onSignIn: () -> Unit,
    snackbars: SnackbarHostState,
) {
    val session by remember(graph) { graph.session.observeSession() }.collectAsScreenState(initial = null)
    var typed by rememberSaveable { mutableStateOf<String?>(null) }
    val content =
        when (owner) {
            ProfileOwner.Me ->
                when (session) {
                    null -> ProfileContent.Loading
                    Session.SignedOut -> ProfileContent.SignedOut
                    else -> standIn.own
                }
            is ProfileOwner.Other -> standIn.other(owner.name)
        }
    val name = (owner as? ProfileOwner.Other)?.name
    val state =
        ProfileState(
            owner = owner,
            content = content,
            sessionExpired = session is Session.Expired,
            openProfile = typed?.let { OpenProfile(it, standIn.recent) },
        )
    ProfileScreen(state, snackbars = snackbars, onEvent = { event ->
        when (event) {
            ProfileEvent.Back -> navigator.back()
            ProfileEvent.OpenSettings -> navigator.open(ProfileNotBuiltKey(ProfileNotBuilt.Settings))
            ProfileEvent.SignIn -> onSignIn()
            ProfileEvent.Retry, ProfileEvent.Refresh -> Unit
            is ProfileEvent.OpenStatistics -> navigator.open(ProfileNotBuiltKey(event.screen.notBuilt(), name))
            ProfileEvent.ViewList -> navigator.open(ProfileNotBuiltKey(ProfileNotBuilt.UserList, name))
            is ProfileEvent.StartOpenProfile -> typed = event.name
            is ProfileEvent.TypeName -> typed = event.text
            is ProfileEvent.OpenProfile -> {
                typed = null
                standIn.opened(event.name)
                navigator.open(OtherProfileKey(event.name))
            }
            ProfileEvent.DismissOpenProfile -> typed = null
        }
    })
}

private fun ProfileStatistics.notBuilt(): ProfileNotBuilt =
    when (this) {
        ProfileStatistics.ScoreDistribution -> ProfileNotBuilt.ScoreDistribution
        ProfileStatistics.Genres -> ProfileNotBuilt.Genres
        ProfileStatistics.FormatsAndYears -> ProfileNotBuilt.FormatsAndYears
    }

@Composable
internal fun ProfileNotBuiltScreen(
    key: ProfileNotBuiltKey,
    onBack: () -> Unit,
    snackbars: SnackbarHostState,
) {
    val title =
        when (key.screen) {
            ProfileNotBuilt.Settings -> stringResource(R.string.profile_settings)
            ProfileNotBuilt.ScoreDistribution -> stringResource(ProfileStatistics.ScoreDistribution.label())
            ProfileNotBuilt.Genres -> stringResource(ProfileStatistics.Genres.label())
            ProfileNotBuilt.FormatsAndYears -> stringResource(ProfileStatistics.FormatsAndYears.label())
            ProfileNotBuilt.UserList ->
                key.name?.let { stringResource(R.string.list_title_other, it) }
                    ?: stringResource(R.string.list_title)
        }
    NotBuiltScreen(title, onBack, snackbars)
}
