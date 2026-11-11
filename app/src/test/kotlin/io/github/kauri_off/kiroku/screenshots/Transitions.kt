package io.github.kauri_off.kiroku.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import org.robolectric.shadows.ShadowChoreographer
import java.io.File

// A change from the case [start] to the case [case], shown as four frames in one image: start, one third, two
// thirds and end of [millis] from the first frame that moves (ARCHITECTURE 8.5). [content] shows the start state,
// then the end state.
class Transition(
    val case: String,
    val start: String,
    val millis: Long,
    val variant: Variant = Variant.EnLight,
    val content: @Composable (changed: Boolean) -> Unit,
)

internal fun transitionFrames(transition: Transition): List<Bitmap> {
    val changed = mutableStateOf(false)
    return Shot.launch(transition.variant) { transition.content(changed.value) }.use { shot ->
        val start = shot.image()
        // Unpaused, Robolectric runs every pending frame at once, so a whole animation would pass in one step.
        ShadowChoreographer.setPaused(true)
        try {
            Snapshot.withMutableSnapshot { changed.value = true }
            Snapshot.sendApplyNotifications()
            // The frames are timed from the first one that differs, not from the frames it takes to start.
            var waited = 0L
            while (waited < transition.millis && shot.frame().sameAs(start)) {
                shot.advance(FRAME_MILLIS)
                waited += FRAME_MILLIS
            }
            val third = transition.millis / 3
            listOf(start) +
                listOf(third, third, transition.millis - 2 * third).map { step ->
                    shot.advance(step)
                    shot.frame()
                }
        } finally {
            shot.advance(SETTLE_MILLIS)
            ShadowChoreographer.setPaused(false)
        }
    }
}

// Renders the frames image and reports a first or last frame that is not the still image of its case.
internal fun renderTransition(
    transition: Transition,
    cases: List<ScreenshotCase>,
    dir: File,
    write: (Bitmap, File) -> Unit = Shot::write,
): List<String> {
    val frames = transitionFrames(transition)
    val name = "${transition.case}__${transition.variant.id}__frames"
    val problems =
        listOf("first" to (transition.start to frames.first()), "last" to (transition.case to frames.last())).mapNotNull { (which, pair) ->
            val (caseName, frame) = pair
            val case = cases.firstOrNull { it.name == caseName } ?: return@mapNotNull "$name: there is no case '$caseName'"
            val still = Shot.launch(transition.variant, keyboard = case.keyboard, content = case.content).use { it.image() }
            if (still.sameAs(frame)) null else "$name: the $which frame is not ${caseName}__${transition.variant.id}.png"
        }
    val gap = frames.first().width / GAP_FRACTION
    val image =
        Bitmap.createBitmap(
            frames.sumOf { it.width } + gap * (frames.size - 1),
            frames.maxOf { it.height },
            Bitmap.Config.ARGB_8888,
        )
    val canvas = Canvas(image)
    canvas.drawColor(GAP_COLOR)
    var x = 0f
    frames.forEach {
        canvas.drawBitmap(it, x, 0f, null)
        x += it.width + gap
    }
    dir.mkdirs()
    write(image, File(dir, "$name.png"))
    return problems
}

private const val GAP_FRACTION = 40
private const val GAP_COLOR = 0xFFFF00FF.toInt()
private const val SETTLE_MILLIS = 2_000L
private const val FRAME_MILLIS = 16L
