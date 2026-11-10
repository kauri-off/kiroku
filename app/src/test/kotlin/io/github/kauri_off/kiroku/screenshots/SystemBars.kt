package io.github.kauri_off.kiroku.screenshots

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Window
import androidx.core.view.WindowCompat

object SystemBars {
    const val HEIGHT_DP = 24
    const val KEYBOARD_DP = 300

    private const val KEYBOARD_LIGHT = 0xFFDDE1E6.toInt()
    private const val KEYBOARD_DARK = 0xFF2B2D31.toInt()

    private const val DARK_ICONS = 0xFF1F1F1F.toInt()
    private const val LIGHT_ICONS = 0xFFF2F2F2.toInt()

    // Robolectric draws no system bars by default; its SystemUi gives a 24 dp status bar and a 24 dp gesture bar.
    fun simulate() {
        val systemUi = Class.forName("org.robolectric.shadows.SystemUi")
        val instance = systemUi.getMethod("systemUiForDefaultDisplay").apply { isAccessible = true }.invoke(null)
        val statusBar = systemUi.getField("STANDARD_STATUS_BAR").apply { isAccessible = true }.get(null)
        val gestureBar = systemUi.getField("GESTURAL_NAVIGATION").apply { isAccessible = true }.get(null)
        systemUi.declaredMethods
            .single { it.name == "setBehavior" && it.parameterCount == 2 }
            .apply { isAccessible = true }
            .invoke(instance, statusBar, gestureBar)
    }

    fun draw(
        canvas: Canvas,
        window: Window,
        density: Float,
        keyboard: Boolean,
    ) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        drawStatusBar(canvas, density, if (controller.isAppearanceLightStatusBars) DARK_ICONS else LIGHT_ICONS)
        if (keyboard) {
            val night = window.context.resources.configuration.isNightModeActive
            val paint = Paint().apply { color = keyboardColor(night) }
            canvas.drawRect(0f, canvas.height - KEYBOARD_DP * density, canvas.width.toFloat(), canvas.height.toFloat(), paint)
            drawGestureHandle(canvas, density, if (night) LIGHT_ICONS else DARK_ICONS)
        } else {
            drawGestureHandle(canvas, density, if (controller.isAppearanceLightNavigationBars) DARK_ICONS else LIGHT_ICONS)
        }
    }

    fun keyboardColor(night: Boolean): Int = if (night) KEYBOARD_DARK else KEYBOARD_LIGHT

    private fun drawStatusBar(
        canvas: Canvas,
        density: Float,
        color: Int,
    ) {
        fun dp(value: Float) = value * density
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = dp(14f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
        canvas.drawText("12:00", dp(16f), dp(17f), paint)

        val right = canvas.width - dp(16f)
        val battery = RectF(right - dp(20f), dp(7f), right - dp(2f), dp(17f))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        canvas.drawRoundRect(battery, dp(2f), dp(2f), paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(right - dp(2f), dp(10f), right, dp(14f), paint)
        canvas.drawRect(battery.left + dp(2f), battery.top + dp(2f), battery.left + dp(12f), battery.bottom - dp(2f), paint)

        val signalRight = battery.left - dp(6f)
        val signal =
            Path().apply {
                moveTo(signalRight, dp(6f))
                lineTo(signalRight, dp(18f))
                lineTo(signalRight - dp(12f), dp(18f))
                close()
            }
        canvas.drawPath(signal, paint)
    }

    private fun drawGestureHandle(
        canvas: Canvas,
        density: Float,
        color: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val width = 108 * density
        val height = 4 * density
        val centerY = canvas.height - HEIGHT_DP * density / 2
        val left = (canvas.width - width) / 2
        canvas.drawRoundRect(RectF(left, centerY - height / 2, left + width, centerY + height / 2), height / 2, height / 2, paint)
    }
}
