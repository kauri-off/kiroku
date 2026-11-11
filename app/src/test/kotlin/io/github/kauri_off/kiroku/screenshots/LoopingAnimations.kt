package io.github.kauri_off.kiroku.screenshots

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

// Every looping animation of a rendered window gets its first frame and one frame HELD_AT_NANOS later, and is then
// held there for good, so the render can idle. The shot counts the loops that are running.
@OptIn(InternalComposeUiApi::class)
object LoopingAnimations : InfiniteAnimationPolicy {
    // Half of the indeterminate circular indicator's cycle, where its arc is longest.
    const val HELD_AT_NANOS = 3_000_000_000L

    private val frameTimes = listOf(0L, HELD_AT_NANOS)
    private val frames = mutableMapOf<Job, Int>()

    val running: Int get() = frames.size

    override suspend fun <R> onInfiniteOperation(block: suspend () -> R): R {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val frame = frames[job] ?: 0.also { job.invokeOnCompletion { frames.remove(job) } }
        frames[job] = frame + 1
        if (frame < frameTimes.size) return withContext(FixedFrame(frameTimes[frame])) { block() }
        return awaitCancellation()
    }

    private class FixedFrame(
        private val nanos: Long,
    ) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = onFrame(nanos)
    }

    fun install() {
        frames.clear()
        WindowRecomposerPolicy.setFactory { view -> view.createLifecycleAwareWindowRecomposer(this) }
    }

    fun uninstall() {
        WindowRecomposerPolicy.setFactory(WindowRecomposerFactory.LifecycleAware)
        frames.clear()
    }
}
