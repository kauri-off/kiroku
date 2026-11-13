package io.github.kauri_off.kiroku

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration

// Only a start has the system's splash to keep; a restored or recreated window is drawn at once.
fun splashHold(
    view: View,
    savedInstanceState: Bundle?,
): SplashHold? = if (savedInstanceState == null) SplashHold(view, atMost = PATIENCE) else null

// The window draws nothing while a screen holds it, for at most [atMost], and never again once it has drawn.
class SplashHold(
    private val view: View,
    atMost: Duration,
) : ViewTreeObserver.OnPreDrawListener {
    private var holds = 0
    private var over = false
    private val drawn = CompletableDeferred<Unit>()

    init {
        view.viewTreeObserver.addOnPreDrawListener(this)
        Handler(Looper.getMainLooper()).postDelayed(::end, atMost.inWholeMilliseconds)
    }

    fun hold() {
        holds++
    }

    fun release() {
        if (--holds == 0) end()
    }

    val keeping: Boolean get() = !over && holds > 0

    override fun onPreDraw(): Boolean {
        if (keeping) return false
        end()
        return true
    }

    // Returns after the frame the window draws once the hold is over.
    suspend fun awaitDrawn() = drawn.await()

    private fun end() {
        if (over) return
        over = true
        view.viewTreeObserver.removeOnPreDrawListener(this)
        view.invalidate()
        view.post { drawn.complete(Unit) }
    }
}

val LocalSplashHold = staticCompositionLocalOf<SplashHold?> { null }

// Composed while the screen has nothing to show yet: the splash stays over it.
@Composable
fun KeepSplash() {
    val hold = LocalSplashHold.current ?: return
    DisposableEffect(hold) {
        hold.hold()
        onDispose { hold.release() }
    }
}
