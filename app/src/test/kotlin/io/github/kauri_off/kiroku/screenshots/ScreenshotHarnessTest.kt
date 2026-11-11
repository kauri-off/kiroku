package io.github.kauri_off.kiroku.screenshots

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Seed
import io.github.kauri_off.kiroku.probe.CentredSmallTarget
import io.github.kauri_off.kiroku.probe.ClockProbe
import io.github.kauri_off.kiroku.probe.DialogProbe
import io.github.kauri_off.kiroku.probe.KeyboardProbe
import io.github.kauri_off.kiroku.probe.OverflowingSheet
import io.github.kauri_off.kiroku.probe.RemoteImageProbe
import io.github.kauri_off.kiroku.probe.STRIP_CARD_DP
import io.github.kauri_off.kiroku.probe.SheetProbe
import io.github.kauri_off.kiroku.probe.StockControls
import io.github.kauri_off.kiroku.probe.StripProbe
import io.github.kauri_off.kiroku.probe.WidthOnlyPoster
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
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.io.path.createTempDirectory

@RunWith(ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotHarnessTest {
    @get:Rule
    val output = TemporaryFolder()

    private val sample: @Composable () -> Unit = { SampleScreen(SampleFixtures.state, onEvent = {}) }

    private val written = mutableMapOf<String, Bitmap>()

    private fun render(vararg cases: ScreenshotCase): List<String> =
        renderScreen("probe", cases.toList(), output.root, { listOf(Variant.EnLight) }) { bitmap, file -> written[file.name] = bitmap }

    private fun Bitmap.hasColour(colour: Int): Boolean = (0 until height).any { getPixel(width / 2, it) == colour }

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

    // Every colour role a case reads is the app theme's for its variant: the first seed, or the second in en-alt.
    // Its typography and shapes are the app theme's too.
    @Test
    fun casesReadTheAppThemesColourRoles() {
        val read = mutableMapOf<Variant, Map<String, String>>()
        for (variant in Variant.entries) {
            var scheme: ColorScheme? = null
            var theme: List<Any>? = null
            var appTheme: List<Any>? = null
            Shot
                .launch(variant) {
                    scheme = MaterialTheme.colorScheme
                    theme = listOf(MaterialTheme.typography, MaterialTheme.shapes)
                    KirokuTheme(variant.dark, dynamicColor = false, variant.seed) {
                        appTheme = listOf(MaterialTheme.typography, MaterialTheme.shapes)
                    }
                }.use { }
            read[variant] = roles(checkNotNull(scheme))
            assertEquals(variant.id, roles(variant.seed.scheme(variant.dark)), read[variant])
            assertEquals(variant.id, checkNotNull(appTheme), theme)
        }
        assertEquals(Seed.Amber, Variant.EnAlt.seed)
        assertTrue(Variant.entries.filter { it != Variant.EnAlt }.all { it.seed == Seed.Teal })
        assertEquals(3, listOf(Variant.EnLight, Variant.EnDark, Variant.EnAlt).map { read[it] }.distinct().size)
    }

    private fun roles(scheme: ColorScheme): Map<String, String> =
        ColorScheme::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
            .associate { it.name.substringBefore('-') to java.lang.Long.toHexString(it.invoke(scheme) as Long) }
            .also { check(it.size >= ROLES) { "Found ${it.size} colour roles" } }

    @Test(timeout = 60_000)
    fun aCircularIndicatorIsHeldWhereItReadsAsASpinner() {
        var arc = 0
        val image =
            Shot
                .launch(Variant.EnLight) {
                    arc = MaterialTheme.colorScheme.primary.toArgb()
                    CircularProgressIndicator()
                }.use { it.image() }
        val ring = Math.PI * (RING_OUTER_DP * RING_OUTER_DP - RING_INNER_DP * RING_INNER_DP) * DENSITY * DENSITY
        val drawn = (0 until image.height).sumOf { y -> (0 until image.width).count { x -> image.getPixel(x, y) == arc } }
        assertTrue("$drawn of ${ring.toInt()} px", drawn > ring * SPINNER_SHARE)
    }

    @Test
    fun anIndicatorRunsAsManyLoopsAsTheRenderAllowsIt() {
        val indicators =
            listOf<Pair<Int, @Composable () -> Unit>>(
                1 to { CircularProgressIndicator() },
                1 to { LinearProgressIndicator() },
                Shot.LOOPS_PER_INDICATOR to { LinearWavyProgressIndicator() },
            )
        for ((loops, indicator) in indicators) {
            Shot.launch(Variant.EnLight, content = indicator).use { shot ->
                shot.image()
                assertEquals(loops, LoopingAnimations.running)
            }
        }
    }

    @Test(timeout = 60_000)
    fun theLoadingIndicatorIsAStillShape() {
        val renders =
            List(2) {
                Shot.launch(Variant.EnLight) { ScreenState(ScreenState.Loading) }.use { shot ->
                    val image = shot.image()
                    assertEquals(0, LoopingAnimations.running)
                    image
                }
            }
        assertTrue(renders[0].sameAs(renders[1]))
        val centre = renders[0].getPixel(renders[0].width / 2, renders[0].height / 2)
        assertFalse("the indicator is drawn at the centre", centre == renders[0].getPixel(0, renders[0].height / 2))
    }

    @Test(timeout = 60_000)
    fun aLoopThatIsNotAProgressIndicatorFailsTheRender() {
        for (content in listOf<@Composable () -> Unit>({ Pulse() }, {
            Column {
                CircularProgressIndicator()
                Pulse()
                Pulse()
            }
        })) {
            val failure = runCatching { Shot.launch(Variant.EnLight, content = content).use { it.image() } }.exceptionOrNull()
            assertTrue(failure.toString(), failure is IllegalStateException && "may loop" in failure.message.orEmpty())
        }
    }

    @Composable
    private fun Pulse() {
        val alpha by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(PULSE_MS)))
        Box(Modifier.size(PULSE_DP.dp).graphicsLayer { this.alpha = alpha })
    }

    @Test
    fun accessibilityResultIsWrittenNextToTheImages() {
        render(contentCase { StockControls() })
        assertTrue(File(output.root, ACCESSIBILITY_FILE).readText().startsWith("passed"))
        render(contentCase { CentredSmallTarget() })
        assertTrue(File(output.root, ACCESSIBILITY_FILE).readText().contains("TouchTargetSize"))
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
                    "LocalDate.now" ->
                        FixtureTime.reference
                            .atZone(FixtureTime.zone)
                            .toLocalDate()
                            .toString()
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
        assertEquals(
            LocalDate
                .of(2026, 1, 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
            month,
        )
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
    fun fullHeightOfHalfExpandedSheetShowsItsEnd() {
        var marker = 0
        val problems =
            render(contentCase { SheetProbe(rows = 30, rowsBehind = 0, skipPartiallyExpanded = false, onEndMarker = { marker = it }) })
        assertEquals(emptyList<String>(), problems)
        assertFalse(image("content__en-light.png").hasColour(marker))
        assertTrue(image("content__en-light__full.png").hasColour(marker))
    }

    @Test
    fun overlayRunningPastTheWindowFailsTheRender() {
        val failure = runCatching { render(contentCase { OverflowingSheet() }) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IllegalStateException && "past the window" in failure.message.orEmpty())
        assertFalse("content__en-light__full.png" in written)
    }

    @Test
    fun notShownReasonsAreWrittenNextToTheImages() {
        renderScreen("probe", emptyList(), output.root, notShown = listOf("error" to "the sample has no network")) { _, _ -> }
        assertEquals("error: the sample has no network\n", File(output.root, NOT_SHOWN_FILE).readText())
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
    fun aCasesWindowReplacesTheVariantsScreenOnly() {
        val images = mutableListOf<Bitmap>()
        var fontScale = 0f
        val wide = case("wide", window = WindowSize(widthDp = 840, heightDp = 600)) { fontScale = LocalDensity.current.fontScale }
        val dir = createTempDirectory().toFile()
        renderScreen("probe", listOf(wide), dir, variantsOf = { listOf(Variant.RuStress) }) { image, _ -> images += image }
        dir.deleteRecursively()
        assertEquals(listOf(IntSize((840 * DENSITY).toInt(), (600 * DENSITY).toInt())), images.map { IntSize(it.width, it.height) })
        assertEquals(Variant.RuStress.fontScale, fontScale)
    }

    @Test
    fun posterSizedByWidthKeepsAPostersProportions() {
        var size = IntSize.Zero
        Shot.launch(Variant.EnLight) { WidthOnlyPoster { size = it } }.use { }
        assertEquals(size.width * RemoteImages.POSTER_HEIGHT / RemoteImages.POSTER_WIDTH.toFloat(), size.height.toFloat(), 1f)
    }

    @Test
    fun remoteImageIsAFlatBlockNoSurfaceUses() {
        val image = Shot.launch(Variant.EnLight) { RemoteImageProbe() }.use { it.image() }
        assertEquals(RemoteImages.BLOCK, image.getPixel(image.width / 2, image.height / 2))
        val surfaces =
            Seed.entries.flatMap { listOf(it.light, it.dark) }.flatMap {
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

private const val ROLES = 48
private const val DENSITY = 2.0
private const val RING_OUTER_DP = 20.0
private const val RING_INNER_DP = 16.0
private const val SPINNER_SHARE = 0.6
private const val PULSE_MS = 1000
private const val PULSE_DP = 48
