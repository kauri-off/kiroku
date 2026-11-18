package io.github.kauri_off.kiroku.statistics

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kauri_off.kiroku.CountAsShown
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.profile.ProfileNotBuilt
import io.github.kauri_off.kiroku.profile.ProfileNotBuiltKey
import io.github.kauri_off.kiroku.wiring.AppGraph

// The page a statistics row of the Profile opens, of the user its key names or of the signed-in user.
@Composable
fun StatisticsRoute(
    page: StatisticsPage,
    name: String?,
    graph: AppGraph,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    snackbars: SnackbarHostState,
) {
    val statistics =
        viewModel {
            StatisticsViewModel(page, name, graph.session, graph.list, graph.user, graph.connection, graph.background)
        }
    val state by statistics.state.collectAsScreenState()
    CountAsShown(statistics.shown)
    StatisticsScreen(state, snackbars = snackbars, onEvent = { event ->
        statistics.onEvent(event)
        when (event) {
            StatisticsEvent.Back -> onBack()
            StatisticsEvent.SignIn -> onSignIn()
            StatisticsEvent.Retry -> Unit
        }
    })
}

// The statistics page the Profile's key opens, or null for a screen still to be built.
val ProfileNotBuiltKey.statisticsPage: StatisticsPage?
    get() =
        when (screen) {
            ProfileNotBuilt.ScoreDistribution -> StatisticsPage.Scores
            ProfileNotBuilt.Genres -> StatisticsPage.Genres
            ProfileNotBuilt.FormatsAndYears -> StatisticsPage.FormatsAndYears
            ProfileNotBuilt.UserList -> null
        }
