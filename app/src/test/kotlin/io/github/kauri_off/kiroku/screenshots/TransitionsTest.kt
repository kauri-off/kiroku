package io.github.kauri_off.kiroku.screenshots

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.kauri_off.kiroku.design.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransitionsTest {
    @get:Rule
    val output = TemporaryFolder()

    private val written = mutableMapOf<String, Bitmap>()

    private val cases = listOf(case("start") { Fading(false) }, case("end") { Fading(true) })

    private fun render(transition: Transition) =
        renderTransition(transition, cases, output.root) { bitmap, file ->
            written[file.name] =
                bitmap
        }

    private fun fading(
        millis: Long,
        start: String = "start",
    ) = Transition("end", start, millis) { Fading(it) }

    @Test
    fun framesThatStartAndEndOnTheStillImagesPass() {
        assertEquals(emptyList<String>(), render(fading(SETTLED)))
        val frames = transitionFrames(fading(SETTLED))
        assertFalse("the one-third frame shows the change under way", frames[1].sameAs(frames[0]) || frames[1].sameAs(frames[3]))
        assertEquals(setOf("end__en-light__frames.png"), written.keys)
    }

    @Test
    fun aChangeStillRunningAtTheEndFails() {
        assertEquals(listOf("end__en-light__frames: the last frame is not end__en-light.png"), render(fading(SHORT)))
    }

    @Test
    fun framesThatStartElsewhereFail() {
        assertEquals(listOf("end__en-light__frames: the first frame is not end__en-light.png"), render(fading(SETTLED, start = "end")))
        assertEquals(listOf("end__en-light__frames: there is no case 'gone'"), render(fading(SETTLED, start = "gone")))
    }

    private companion object {
        val SETTLED = Motion.Duration.MEDIUM4.toLong()
        val SHORT = Motion.Duration.SHORT2.toLong()
    }
}

@Composable
private fun Fading(shown: Boolean) {
    val alpha by animateFloatAsState(if (shown) 1f else 0f, Motion.componentChange())
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)))
}
