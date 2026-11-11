package io.github.kauri_off.kiroku.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import java.io.File

// A change shown as four frames in one image: start, one third, two thirds and end of [millis] (ARCHITECTURE 8.5).
class Transition(
    val case: String,
    val millis: Long,
    val variant: Variant = Variant.EnLight,
    val content: @Composable (changed: Boolean) -> Unit,
)

internal fun renderTransition(
    transition: Transition,
    dir: File,
    write: (Bitmap, File) -> Unit = Shot::write,
) {
    val changed = mutableStateOf(false)
    val frames =
        Shot.launch(transition.variant) { transition.content(changed.value) }.use { shot ->
            val start = shot.image()
            Snapshot.withMutableSnapshot { changed.value = true }
            Snapshot.sendApplyNotifications()
            val third = transition.millis / 3
            listOf(start) +
                listOf(third, third, transition.millis - 2 * third).map { step ->
                    shot.advance(step)
                    shot.frame()
                }
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
    write(image, File(dir, "${transition.case}__${transition.variant.id}__frames.png"))
}

private const val GAP_FRACTION = 40
private const val GAP_COLOR = 0xFFFF00FF.toInt()
