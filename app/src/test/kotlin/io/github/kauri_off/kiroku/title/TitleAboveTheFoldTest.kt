package io.github.kauri_off.kiroku.title

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.design.component.DrawnTitleKey
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.ThemeSettings
import io.github.kauri_off.kiroku.design.theme.setKirokuContent
import io.github.kauri_off.kiroku.screenshots.SystemBars
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// ARCHITECTURE 9.4: on a 411 × 891 dp screen with its system bars, the header, the list block and the airing block of
// a title of up to two lines end above the gesture bar, in both languages of the base variants.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TitleAboveTheFoldTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule =
        RuleChain
            .outerRule(
                object : TestWatcher() {
                    override fun starting(description: Description) = SystemBars.simulate()
                },
            ).around(registered(compose))

    private var state by mutableStateOf<TitleState>(TitleState.Loading(Fixtures.reference.session))
    private var gestureBarPx = -1

    private fun start() {
        compose.runOnUiThread {
            compose.activity.setKirokuContent(ThemeSettings(dynamicColor = false)) {
                gestureBarPx = WindowInsets.navigationBars.getBottom(LocalDensity.current)
                TitleInShell(state, onEvent = {})
            }
        }
        compose.waitForIdle()
    }

    private fun show(shown: TitleState.Shown): Int {
        compose.runOnIdle { state = shown }
        compose.waitForIdle()
        val layout =
            compose
                .onNodeWithText(shown.page.title)
                .fetchSemanticsNode()
                .config[DrawnTitleKey]
                .layout
        return checkNotNull(layout).lineCount
    }

    private fun overflows(shown: TitleState.Shown): List<String> {
        val gestureBar = with(compose.density) { gestureBarPx.toDp() }
        val fold = compose.onRoot().getUnclippedBoundsInRoot().bottom - gestureBar
        val tags = listOfNotNull(TitleTags.header, TitleTags.list, TitleTags.airing.takeIf { shown.page.airing != null })
        return tags.mapNotNull { tag ->
            val bottom = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().bottom
            "${shown.page.title} (${shown.session}): $tag ends at $bottom, below the fold at $fold".takeIf { bottom > fold }
        }
    }

    // The worst case: the session banner, on the list, two airing lines, a rank, and the longest title that still takes
    // two lines.
    private fun longestTwoLineTitle(): TitleState.Shown {
        val anime = FixtureAnime.exactToWatch
        val words =
            FixtureAnime.longTitle.titles.romaji
                .split(" ")
        var longest: TitleState.Shown? = null
        for (count in 1..words.size) {
            val title = words.take(count).joinToString(" ")
            val shown = TitleFixtures.of(anime.copy(titles = anime.titles.copy(romaji = title)), session = Fixtures.reference.expired)
            val lines = show(shown)
            if (lines > 2) break
            if (lines == 2) longest = shown
        }
        return checkNotNull(longest) { "no title of two lines" }
    }

    private fun checkTheFold() {
        start()
        assertEquals(SystemBars.HEIGHT_DP.dp, with(compose.density) { gestureBarPx.toDp() })
        val cases =
            listOf(
                TitleFixtures.of(FixtureAnime.exactToWatch),
                TitleFixtures.of(FixtureAnime.notOnListAiring),
                TitleFixtures.of(FixtureAnime.estimatedToWatch),
                TitleFixtures.of(FixtureAnime.estimatedCaughtUp),
                TitleFixtures.of(FixtureAnime.airedUnknown),
                TitleFixtures.of(FixtureAnime.premiereTimeSoon),
                TitleFixtures.of(FixtureAnime.premiereTimeLater),
                TitleFixtures.of(FixtureAnime.premiereDate),
                TitleFixtures.of(FixtureAnime.premiereMonth),
                TitleFixtures.of(FixtureAnime.premiereUnannounced),
                TitleFixtures.of(FixtureAnime.exactCaughtUp),
                TitleFixtures.of(FixtureAnime.longTitle),
                TitleFixtures.plannedScored,
                TitleFixtures.firstEpisodeAired,
                TitleFixtures.added,
                TitleFixtures.of(FixtureAnime.exactToWatch, session = Fixtures.reference.expired),
            )
        val problems = mutableListOf<String>()
        var twoLineCases = 0
        for (shown in cases) {
            if (show(shown) > 2) continue
            twoLineCases++
            problems += overflows(shown)
        }
        val longest = longestTwoLineTitle()
        assertEquals(2, show(longest))
        problems += overflows(longest)
        assertEquals("cases of up to two lines", cases.size - 1, twoLineCases)
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xhdpi")
    fun theFirstThreeBlocksAreAboveTheFoldInEnglish() = checkTheFold()

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp-xhdpi")
    fun theFirstThreeBlocksAreAboveTheFoldInRussian() = checkTheFold()
}
