package io.github.kauri_off.kiroku.screenshots

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import kotlinx.coroutines.awaitCancellation

// A looping animation, such as an indeterminate progress indicator, keeps Robolectric's paused looper busy forever.
// Rendered windows hold every looping animation at its first frame instead.
@OptIn(InternalComposeUiApi::class)
object StillAnimations : InfiniteAnimationPolicy {
    override suspend fun <R> onInfiniteOperation(block: suspend () -> R): R = awaitCancellation()

    fun install() = WindowRecomposerPolicy.setFactory { view -> view.createLifecycleAwareWindowRecomposer(this) }

    fun uninstall() = WindowRecomposerPolicy.setFactory(WindowRecomposerFactory.LifecycleAware)
}
