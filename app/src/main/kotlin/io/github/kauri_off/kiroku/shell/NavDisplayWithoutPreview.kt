package io.github.kauri_off.kiroku.shell

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.NavigationBackHandler
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberNavigationEventState
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay

// ARCHITECTURE 8.5: the back gesture feeds the handler's state only, so a commit is an ordinary pop with [transition].
@Composable
internal fun NavDisplayWithoutPreview(
    entries: List<NavEntry<NavKey>>,
    transition: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform,
    onBack: () -> Unit,
) {
    val scenes = rememberSceneState(entries, listOf(SinglePaneSceneStrategy()), onBack = onBack)
    val handled = rememberNavigationEventState(scenes)
    val shown = rememberNavigationEventState(scenes)
    NavigationBackHandler(scenes, handled, onBackCompleted = onBack)
    NavDisplay(scenes, shown, transitionSpec = transition, popTransitionSpec = transition)
}
