package io.github.kauri_off.kiroku.screenshots

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.probe.ClockProbe
import io.github.kauri_off.kiroku.probe.DialogProbe
import io.github.kauri_off.kiroku.probe.KeyboardProbe
import io.github.kauri_off.kiroku.probe.RemoteImageProbe
import io.github.kauri_off.kiroku.probe.STRIP_CARD_DP
import io.github.kauri_off.kiroku.probe.SheetProbe
import io.github.kauri_off.kiroku.probe.StripProbe
import io.github.kauri_off.kiroku.probe.wallClockReadings
import io.github.kauri_off.kiroku.sample.SampleFixtures
import io.github.kauri_off.kiroku.sample.SampleScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone

@RunWith(ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotHarnessTest {
    @get:Rule
    val output = TemporaryFolder()

    private val sample: @Composable () -> Unit = { SampleScreen(SampleFixtures.state, onEvent = {}) }

    private val written = mutableMapOf<String, Bitmap>()

    private fun render(vararg cases: ScreenshotCase): List<String> =
        renderScreen("probe", cases.toList(), output.root, { listOf(Variant.EnLight) }) { bitmap, file -> written[file.name] = bitmap }

    private fun image(name: String): Bitmap = written[name] ?: error("$name was not written; written: ${written.keys}")

    @Test
    fun windowHasBothSystemBarInsets() {
        var status = -1
        var navigation = -1
        Shot
            .launch(Variant.EnLight) {
                val density = LocalDensity.current
                status = WindowInsets.statusBars.getTop(density)
                navigation = WindowInsets.navigationBars.getBottom(density)
            }.use { shot ->
                assertEquals(shot.dpToPx(SystemBars.HEIGHT_DP).toInt(), status)
                assertEquals(shot.dpToPx(SystemBars.HEIGHT_DP).toInt(), navigation)
            }
    }

    @Test
    fun twoRendersAreIdentical() {
        for (content in listOf(sample, @Composable { ClockProbe() })) {
            val first = Shot.launch(Variant.RuStress, content = content).use { it.image() }
            val second = Shot.launch(Variant.RuStress, content = content).use { it.image() }
            assertTrue(first.sameAs(second))
        }
    }

    @Test
    fun everyWallClockGivesTheFixturesMoment() {
        assertEquals(FixtureTime.reference, FixedWallClock.instant)
        val readings = Shot.launch(Variant.EnLight) { androidx.compose.material3.Text("x") }.use { wallClockReadings() }
        val moment = FixtureTime.reference.toString()
        val relative =
            DateUtils
                .getRelativeTimeSpanString(
                    FixtureTime.reference.minus(Duration.ofDays(3)).toEpochMilli(),
                    FixtureTime.reference.toEpochMilli(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
        val expected =
            readings.mapValues { (source, _) ->
                when (source) {
                    "LocalDate.now" -> FixtureTime.reference.atZone(FixtureTime.zone).toLocalDate().toString()
                    "DateUtils" -> relative
                    else -> moment
                }
            }
        assertEquals(expected, readings)
    }

    @Test
    fun datePickerOpensOnTheFixturesMonth() {
        var month = 0L
        Shot.launch(Variant.EnLight) { ClockProbe { month = it } }.use { }
        assertEquals(LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), month)
    }

    @Test
    fun launchRestoresTheMachinesZone() {
        val before = TimeZone.getDefault()
        Shot.launch(Variant.EnLight, content = sample).use { assertEquals(FixtureTime.zone, TimeZone.getDefault().toZoneId()) }
        assertEquals(before, TimeZone.getDefault())
    }

    @Test
    fun fullHeightShowsTheLastRowClearOfTheGestureBar() {
        val extra = Shot.launch(Variant.EnLight, content = sample).use { it.scrollRangeDp() }
        assertTrue(extra > 0)
        Shot.launch(Variant.EnLight, Variant.EnLight.heightDp + extra, content = sample).use { shot ->
            val bottom = shot.scrolledContentBottomPx()
            assertEquals(0, shot.scrollRangeDp())
            assertTrue(bottom <= shot.windowHeightPx - shot.dpToPx(SystemBars.HEIGHT_DP))
            assertTrue(bottom > shot.windowHeightPx - shot.dpToPx(SystemBars.HEIGHT_DP + 2))
        }
    }

    @Test
    fun sheetDrawsItsContainerBehindTheGestureBar() {
        for (variant in listOf(Variant.EnLight, Variant.EnDark)) {
            var container = 0
            val image = Shot.launch(variant) { SheetProbe(rows = 3, rowsBehind = 40) { container = it } }.use { it.image() }
            val x = image.width / 10
            assertEquals(Integer.toHexString(container), Integer.toHexString(image.getPixel(x, image.height - 1)))
        }
    }

    @Test
    fun fullHeightOfSheetComesFromTheSheet() {
        render(contentCase { SheetProbe(rows = 30, rowsBehind = 0) })
        val full = image("content__en-light__full.png")
        assertTrue(full.height > image("content__en-light.png").height)
    }

    @Test
    fun dialogThatDoesNotScrollHasNoFullHeight() {
        render(contentCase { DialogProbe(smallTarget = false) })
        assertTrue(written.isNotEmpty())
        assertFalse("content__en-light__full.png" in written)
    }

    @Test
    fun stripIsStitchedWithEveryCard() {
        render(contentCase { StripProbe() })
        val strip = image("content__en-light__strip-1.png")
        assertTrue(strip.width.toString(), strip.width >= 12 * STRIP_CARD_DP * 2)
    }

    @Test
    fun keyboardCoversTheBottomAndPushesImePaddedContentUp() {
        var ime = -1
        var fieldBottom = -1f
        Shot.launch(Variant.EnLight, keyboard = true) { KeyboardProbe({ ime = it }, { fieldBottom = it }) }.use { shot ->
            val image = shot.image()
            val keyboard = shot.dpToPx(SystemBars.KEYBOARD_DP)
            assertEquals(keyboard.toInt(), ime)
            assertTrue(fieldBottom <= shot.windowHeightPx - keyboard + 0.5f)
            assertTrue(fieldBottom > shot.windowHeightPx - keyboard - 1f)
            assertEquals(SystemBars.keyboardColor(night = false), image.getPixel(2, image.height - keyboard.toInt() / 2))
        }
    }

    @Test
    fun remoteImageIsAFlatBlockNoSurfaceUses() {
        val image = Shot.launch(Variant.EnLight) { RemoteImageProbe() }.use { it.image() }
        assertEquals(RemoteImages.BLOCK, image.getPixel(image.width / 2, image.height / 2))
        val surfaces =
            listOf(lightColorScheme(), darkColorScheme()).flatMap {
                listOf(
                    it.surface,
                    it.surfaceVariant,
                    it.surfaceContainerLowest,
                    it.surfaceContainerLow,
                    it.surfaceContainer,
                    it.surfaceContainerHigh,
                    it.surfaceContainerHighest,
                ).map { color -> color.toArgb() }
            }
        assertFalse(RemoteImages.BLOCK in surfaces)
    }
}
