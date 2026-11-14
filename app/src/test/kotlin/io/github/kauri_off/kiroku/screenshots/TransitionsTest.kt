package io.github.kauri_off.kiroku.screenshots

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
        val frames = transitionFrames(fading(SETTLED)).images
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

    @Test
    fun `a change that starts late is reported`() {
        val late = Transition("end", "start", SETTLED) { LateFading(it) }
        val problems = render(late)
        assertEquals(listOf("end__en-light__frames: the change shows 144 ms after the state changed, more than 48 ms"), problems)
    }

    @Test
    fun `frames are taken in the end case's window`() {
        val wide = WindowSize(widthDp = Variant.EnLight.widthDp * 2, heightDp = Variant.EnLight.heightDp)
        val windowed = listOf(case("start", window = wide) { Fading(false) }, case("end", window = wide) { Fading(true) })
        assertEquals(emptyList<String>(), renderTransition(fading(SETTLED), windowed, output.root) { _, _ -> })
        val frames = transitionFrames(fading(SETTLED), wide).images
        assertEquals(2 * transitionFrames(fading(SETTLED)).images[0].width, frames[0].width)
    }

    @Test
    fun `frames can be taken at chosen moments`() {
        val early = Transition("end", "start", SETTLED, at = listOf(FRAME, SETTLED / 6, SETTLED)) { Fading(it) }
        assertEquals(emptyList<String>(), render(early))
        assertEquals(4, transitionFrames(early).images.size)
    }

    @Test
    fun `a lead delays the moments`() {
        val led = Transition("end", "start", SETTLED, lead = FRAME) { Fading(it) }
        assertEquals(emptyList<String>(), render(led))
        assertFalse(transitionFrames(led).images[1].sameAs(transitionFrames(fading(SETTLED)).images[1]))
    }

    @Test
    fun `a change made as input makes it shows as soon as the state it is timed from`() {
        val shown = mutableStateOf(false)
        val byInput = transitionFrames(Transition("end", "start", SETTLED, change = { shown.value = true }) { Fading(shown.value) })
        assertEquals(transitionFrames(fading(SETTLED)).waited, byInput.waited)
    }

    @Test
    fun `two changes that end on one case write two images by their names`() {
        assertEquals(emptyList<String>(), render(fading(SETTLED)))
        assertEquals(emptyList<String>(), render(Transition("end", "start", SETTLED, name = "end-again") { Fading(it) }))
        assertEquals(setOf("end__en-light__frames.png", "end-again__en-light__frames.png"), written.keys)
    }

    private companion object {
        const val FRAME = 16L
        const val SETTLED = 400L
        const val SHORT = 100L
    }
}

@Composable
private fun Fading(shown: Boolean) {
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(FADE_MILLIS))
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)))
}

// Starts fading only some time after the change.
@Composable
private fun LateFading(shown: Boolean) {
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(LATE_FADE_MILLIS, delayMillis = LATE_MILLIS))
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)))
}

private const val FADE_MILLIS = 200
private const val LATE_MILLIS = 100
private const val LATE_FADE_MILLIS = 200
