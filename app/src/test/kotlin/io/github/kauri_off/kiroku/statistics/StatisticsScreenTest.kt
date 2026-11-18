package io.github.kauri_off.kiroku.statistics

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.text.named
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.text.NumberFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class StatisticsScreenTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val fixtures = StatisticsFixtureStates
    private val events = mutableListOf<StatisticsEvent>()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun show(state: StatisticsState) =
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { StatisticsScreen(state, { events += it }, SnackbarHostState()) }
        }

    private fun bar(
        label: String,
        count: Int,
    ) = "$label:\u00A0${NumberFormat.getIntegerInstance(Locale.ENGLISH).format(count)}\u00A0${if (count == 1) "title" else "titles"}"

    // The page's line spoken as [spoken], scrolled to where it is drawn.
    private fun assertLine(spoken: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(spoken))
        compose.onNodeWithContentDescription(spoken).assertExists()
    }

    @Test
    fun theScoresPageShowsTheCalculatorsValuesOverTheFixturesList() {
        val statistics = statisticsOf(fixtures.set.entries)
        show(fixtures.shown(StatisticsPage.Scores, statistics))
        compose.onNode(hasText(String.format(Locale.ENGLISH, "%.2f", statistics.meanScore)) and hasText("Mean score")).assertExists()
        compose.onNode(hasText(statistics.scored.toString()) and hasText("Scored")).assertExists()
        compose.onNode(hasText(statistics.unscored.toString()) and hasText("Not scored")).assertExists()
        assertEquals(fixtures.set.entries.size, statistics.scored + statistics.unscored)
        for (score in 10 downTo 1) assertLine(bar("Score\u00A0$score", statistics.scores[score] ?: 0))
    }

    @Test
    fun theGenresPageShowsEveryGenreTheCalculatorCounted() {
        val statistics = statisticsOf(fixtures.set.entries)
        show(fixtures.shown(StatisticsPage.Genres, statistics))
        assertTrue(statistics.genres.size > 20)
        for (genre in statistics.genres) assertLine(bar(genre.value.named { context.getString(it) }, genre.count))
    }

    @Test
    fun theFormatsPageShowsTheCalculatorsFormatsAndDecadesOfAListOfThousands() {
        val statistics = fixtures.large
        show(fixtures.shown(StatisticsPage.FormatsAndYears, statistics, fixtures.otherName))
        compose.onNodeWithText("Formats").assertExists()
        for (type in statistics.mediaTypes) assertLine(bar(type.value.named { context.getString(it) }, type.count))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Years"))
        assertTrue(statistics.decades.size > 2)
        for (decade in statistics.decades) assertLine(bar("${decade.value}s", decade.count))
    }

    @Test
    fun theMeanRoundsHalfUpAsMalDoes() {
        val scores = listOf(10, 10, 9, 7, 7, 5, 5, 4)
        val entries =
            fixtures.set.entries
                .take(scores.size)
                .mapIndexed { i, it -> it.copy(values = it.values.copy(score = scores[i])) }
        val statistics = statisticsOf(entries)
        assertEquals(57.0 / 8, statistics.meanScore!!, 0.0)
        show(fixtures.shown(StatisticsPage.Scores, statistics))
        compose.onNodeWithText("7.13", useUnmergedTree = true).assertExists()
    }

    @Test
    fun nothingScoredHasNoMeanScoreAndSaysSoInPlaceOfTheBars() {
        show(fixtures.shown(StatisticsPage.Scores, fixtures.nothingScored))
        compose.onNodeWithContentDescription("No mean score", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Nothing scored").assertExists()
        compose.onNodeWithContentDescription(bar("Score\u00A010", 0)).assertDoesNotExist()
    }

    @Test
    fun aListWithNothingOnItSaysSo() {
        var page by mutableStateOf(StatisticsPage.Scores)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                StatisticsScreen(fixtures.shown(page, fixtures.nothing), {}, SnackbarHostState())
            }
        }
        for (each in StatisticsPage.entries) {
            page = each
            compose.onNodeWithText("Nothing on the list yet").assertExists()
        }
    }

    @Test
    fun backAndRetryAreThePersonsWayOut() {
        show(fixtures.of(StatisticsPage.Genres, StatisticsContent.Failed(DataError.NoConnection), fixtures.otherName))
        compose.onNodeWithText("No connection").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf(StatisticsEvent.Retry, StatisticsEvent.Back), events)
    }

    @Test
    fun aPrivateListIsAnAnswerAndOffersNoRetry() {
        show(fixtures.of(StatisticsPage.Genres, StatisticsContent.Failed(DataError.PrivateList), fixtures.otherName))
        compose.onNodeWithText("This list is private").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun signedOutTheOwnPageOffersSignIn() {
        show(fixtures.of(StatisticsPage.Scores, StatisticsContent.SignedOut))
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(listOf(StatisticsEvent.SignIn), events)
    }

    private class Measured(
        val columns: BarColumns,
        val word: Dp,
    )

    // The columns of the page with every genre, and the widest word of their labels as a line of the page measures it.
    private fun measureEveryGenre(width: Dp): Measured {
        var measured: Measured? = null
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val bars = groups(StatisticsPage.Genres, fixtures.everyGenre).flatMap { it.bars }
                val measurer = rememberTextMeasurer()
                val style = MaterialTheme.typography.bodyMedium
                val words = bars.flatMap { it.label.split(' ') }
                val px = words.maxOf { measurer.measure(it, style, softWrap = false).size.width }
                measured = Measured(barColumns(bars, width), (px / context.resources.displayMetrics.density).dp)
            }
        }
        compose.waitForIdle()
        return checkNotNull(measured)
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h640dp-xhdpi", fontScale = 1.3f)
    fun onTheNarrowestScreenTheLongestGenreNamesPutEachBarUnderItsLabel() {
        val width = 360.dp - Spacing.l * 2
        val measured = measureEveryGenre(width)
        assertTrue(measured.columns.stacked)
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp-xhdpi")
    fun onAPhoneTheLongestGenreNamesWrapOnlyBetweenWordsAndLeaveTheBarsTheirShare() {
        val width = 411.dp - Spacing.l * 2
        val measured = measureEveryGenre(width)
        assertFalse(measured.columns.stacked)
        assertTrue(measured.columns.label >= measured.word)
        assertTrue(width - measured.columns.label - measured.columns.count - Spacing.m * 2 >= width * 0.4f)
    }
}
