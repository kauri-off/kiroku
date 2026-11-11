package io.github.kauri_off.kiroku.standin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import kotlin.random.Random

// Answers a fixture title's poster address with art drawn from its id; every other request goes on to the loader.
object StandInPosters : Interceptor {
    const val WIDTH = 225
    const val HEIGHT = 318

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val id = FixtureAnime.posterId(chain.request.data.toString()) ?: return chain.proceed()
        return SuccessResult(draw(id).asImage(), chain.request, DataSource.MEMORY)
    }

    // A sky in two hues, a disc and a ridge, all chosen by the id, so each title is told apart at a glance.
    fun draw(id: Long): Bitmap {
        val random = Random(id)
        val hue = random.nextFloat() * FULL_TURN
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h, hsv(hue, 0.55f, 0.35f), hsv(hue + 50f, 0.45f, 0.85f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.color = hsv(hue + 180f, 0.3f, 0.95f)
        canvas.drawCircle(
            w * (0.25f + random.nextFloat() * 0.5f),
            h * (0.2f + random.nextFloat() * 0.25f),
            w * (0.14f + random.nextFloat() * 0.14f),
            paint,
        )
        val ridge =
            Path().apply {
                moveTo(0f, h)
                lineTo(0f, h * (0.6f + random.nextFloat() * 0.15f))
                val peaks = 2 + random.nextInt(3)
                for (i in 1..peaks) {
                    lineTo(w * (i - 0.5f) / peaks, h * (0.45f + random.nextFloat() * 0.2f))
                    lineTo(w * i / peaks, h * (0.62f + random.nextFloat() * 0.15f))
                }
                lineTo(w, h)
                close()
            }
        paint.color = hsv(hue + 20f, 0.6f, 0.2f)
        canvas.drawPath(ridge, paint)
        return bitmap
    }

    private fun hsv(
        hue: Float,
        saturation: Float,
        value: Float,
    ): Int = Color.HSVToColor(floatArrayOf(hue % FULL_TURN, saturation, value))

    private const val FULL_TURN = 360f
}
