package io.github.kauri_off.kiroku.profile

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.CountAsShown
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.NotBuiltScreen
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.serialization.Serializable

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

// The Profile of the signed-in user, or of the user [key] names; the own name opened as another's is the own Profile.
@Composable
fun ProfileRoute(
    key: OtherProfileKey?,
    graph: AppGraph,
    messages: ShellMessages,
    opened: OpenedProfiles,
    navigator: ShellNavigator,
    onSignIn: () -> Unit,
    snackbars: SnackbarHostState,
) {
    val profile =
        viewModel {
            ProfileViewModel(
                key?.let { ProfileOwner.Other(it.name) } ?: ProfileOwner.Me,
                graph.session,
                graph.list,
                graph.user,
                graph.connection,
                graph.clock,
                graph.background,
                createSavedStateHandle(),
                messages,
                opened,
            )
        }
    val state by profile.state.collectAsScreenState()
    CountAsShown(profile.shown)
    // The dialog is modal, so only a link leaves it open on a screen that goes; it is not there when Back returns.
    val activity = LocalActivity.current
    DisposableEffect(profile) {
        onDispose { if (activity?.isChangingConfigurations != true) profile.onEvent(ProfileEvent.DismissOpenProfile) }
    }
    if (key != null) {
        val own by profile.own.collectAsScreenState()
        LaunchedEffect(own) { if (own) navigator.remove(key) }
    }
    val name = key?.name
    ProfileScreen(state.copy(openProfile = profile.openProfile), snackbars = snackbars, onEvent = { event ->
        profile.onEvent(event)
        when (event) {
            ProfileEvent.Back -> navigator.back()
            ProfileEvent.OpenSettings -> navigator.open(ProfileNotBuiltKey(ProfileNotBuilt.Settings))
            ProfileEvent.SignIn -> onSignIn()
            is ProfileEvent.OpenStatistics -> navigator.open(ProfileNotBuiltKey(event.screen.notBuilt(), name))
            ProfileEvent.ViewList -> navigator.open(ProfileNotBuiltKey(ProfileNotBuilt.UserList, name))
            is ProfileEvent.OpenProfile ->
                if (profile.isOwnName(event.name)) navigator.home(Destination.Profile) else navigator.open(OtherProfileKey(event.name))
            else -> Unit
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
