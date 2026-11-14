package io.github.kauri_off.kiroku.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import org.robolectric.shadows.ShadowChoreographer
import java.io.File

// A change from the case [start] to the case [case], shown as frames in one image: the start, then the moments [at]
// from the first frame that moves, by default one third, two thirds and the end of [millis] (ARCHITECTURE 8.5). A
// change that starts fast can show early moments instead. When one part reacts before the motion shown, [lead]
// says by how long, and the moments are timed from the end of it. [content] shows the start state, then the end
// state, in the end case's window. Its image is named after [name], the end case unless two changes end on one case.
// [change] is made with the state, outside composition, as input makes it.
class Transition(
    val case: String,
    val start: String,
    val millis: Long,
    val variant: Variant = Variant.EnLight,
    val at: List<Long> = listOf(millis / 3, 2 * millis / 3, millis),
    val lead: Long = 0,
    val name: String = case,
    val change: () -> Unit = {},
    val content: @Composable (changed: Boolean) -> Unit,
) {
    init {
        require(at.isNotEmpty() && at.zipWithNext().all { (a, b) -> a < b } && at.last() == millis)
        require(lead in 0..MAX_LEAD_MILLIS)
    }
}

internal class Frames(
    val images: List<Bitmap>,
    // How long the change took to show its first moving frame.
    val waited: Long,
)

internal fun transitionFrames(
    transition: Transition,
    window: WindowSize? = null,
): Frames {
    val changed = mutableStateOf(false)
    val variant = transition.variant
    val shot =
        Shot.launch(
            variant,
            heightDp = window?.heightDp ?: variant.heightDp,
            widthDp = window?.widthDp ?: variant.widthDp,
        ) { transition.content(changed.value) }
    return shot.use {
        val start = shot.image()
        // Unpaused, Robolectric runs every pending frame at once, so a whole animation would pass in one step.
        ShadowChoreographer.setPaused(true)
        try {
            Snapshot.withMutableSnapshot {
                changed.value = true
                transition.change()
            }
            Snapshot.sendApplyNotifications()
            // The frames are timed from the first one that differs, not from the frames it takes to start.
            var waited = 0L
            while (waited < transition.millis && shot.frame().sameAs(start)) {
                shot.advance(FRAME_MILLIS)
                waited += FRAME_MILLIS
            }
            shot.advance(transition.lead)
            var elapsed = 0L
            val frames =
                transition.at.map { moment ->
                    shot.advance(moment - elapsed)
                    elapsed = moment
                    shot.frame()
                }
            Frames(listOf(start) + frames, waited)
        } finally {
            shot.advance(SETTLE_MILLIS)
            ShadowChoreographer.setPaused(false)
        }
    }
}

// Renders the frames image and reports a first or last frame that is not the still image of its case, and a change
// that is late to start.
internal fun renderTransition(
    transition: Transition,
    cases: List<ScreenshotCase>,
    dir: File,
    write: (Bitmap, File) -> Unit = Shot::write,
): List<String> {
    val name = "${transition.name}__${transition.variant.id}__frames"
    val end = cases.firstOrNull { it.name == transition.case } ?: return listOf("$name: there is no case '${transition.case}'")
    val window = end.window
    val result = transitionFrames(transition, window)
    val frames = result.images
    val variant = transition.variant
    val late =
        "$name: the change shows ${result.waited} ms after the state changed, more than ${START_FRAMES * FRAME_MILLIS} ms"
            .takeIf { result.waited > START_FRAMES * FRAME_MILLIS }
    val problems =
        listOf("first" to (transition.start to frames.first()), "last" to (transition.case to frames.last())).mapNotNull { (which, pair) ->
            val (caseName, frame) = pair
            val case = cases.firstOrNull { it.name == caseName } ?: return@mapNotNull "$name: there is no case '$caseName'"
            if (case.window != window) return@mapNotNull "$name: '$caseName' is shown in another window than '${transition.case}'"
            val still =
                Shot
                    .launch(
                        variant,
                        heightDp = window?.heightDp ?: variant.heightDp,
                        keyboard = case.keyboard,
                        widthDp = window?.widthDp ?: variant.widthDp,
                        content = case.content,
                    ).use { it.image() }
            if (still.sameAs(frame)) null else "$name: the $which frame is not ${caseName}__${variant.id}.png"
        } + listOfNotNull(late)
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
private const val MAX_LEAD_MILLIS = 2 * FRAME_MILLIS

// A change may take this many frames to show: one to apply the state, one to start the animation at its start value,
// one to move.
private const val START_FRAMES = 3
