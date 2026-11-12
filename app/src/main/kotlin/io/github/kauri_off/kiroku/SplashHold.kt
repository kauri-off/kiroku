package io.github.kauri_off.kiroku

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.time.Duration

// The window draws nothing while a screen holds it, so the system's splash stays until the first screen has content,
// or for [atMost]. Once the window has drawn, nothing holds it again.
class SplashHold(
    private val view: View,
    atMost: Duration,
) : ViewTreeObserver.OnPreDrawListener {
    private var holds = 0
    private var over = false

    init {
        view.viewTreeObserver.addOnPreDrawListener(this)
        Handler(Looper.getMainLooper()).postDelayed(::end, atMost.inWholeMilliseconds)
    }

    fun hold() {
        holds++
    }

    fun release() {
        holds--
    }

    val keeping: Boolean get() = !over && holds > 0

    override fun onPreDraw(): Boolean {
        if (keeping) return false
        end()
        return true
    }

    private fun end() {
        if (over) return
        over = true
        view.viewTreeObserver.removeOnPreDrawListener(this)
        view.invalidate()
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
