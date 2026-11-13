package io.github.kauri_off.kiroku.titlesub

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureDetails
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.text.statusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class SubScreenTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val snackbars = SnackbarHostState()

    private fun show(content: @Composable () -> Unit) = compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { content() } }

    @Test
    fun aLongEnglishTitleLeavesTheJapaneseTitleAndSynonymsShown() {
        val english = "The Heat Haze Library Season 2: ".repeat(12).trim()
        val anime = FixtureAnime.exactToWatch.copy(titles = FixtureAnime.exactToWatch.titles.copy(english = english))
        val state = detailsState(anime, FixtureDetails.full, TitleLanguage.Romaji, SubScreenFixtures.session, Fixtures.reference.clock)
        show { DetailsScreen(state, onEvent = {}, snackbars = snackbars) }
        for (text in listOf("English title", "Japanese title", "陽炎図書館 第2期", "Synonyms", "Kagetosho 2", "Heat Haze Library II")) {
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun theStatusLineStatesTheCountOnlyWhereItAddsSomething() {
        val lines = mutableListOf<String>()

        fun values(
            status: ListStatus,
            watched: Int,
        ) = ListValues(status, watched, null, null, null)
        show {
            lines += statusLine(values(ListStatus.Watching, 5), 12)
            lines += statusLine(values(ListStatus.Watching, 5), null)
            lines += statusLine(values(ListStatus.Planned, 0), 12)
            lines += statusLine(values(ListStatus.Planned, 2), 12)
            lines += statusLine(values(ListStatus.Completed, 12), 12)
            lines += statusLine(values(ListStatus.OnHold, 3), 12)
        }
        compose.waitForIdle()
        assertEquals(
            listOf(
                "Watching\u00A0· 5\u00A0/\u00A012",
                "Watching\u00A0· 5",
                "Planned",
                "Planned\u00A0· 2\u00A0/\u00A012",
                "Completed",
                "On hold\u00A0· 3\u00A0/\u00A012",
            ),
            lines.take(6),
        )
    }

    @Test
    fun aRelatedRowWithoutMalsCountShowsTheTotalFromTheAiringData() {
        val anime = FixtureAnime.exactToWatch.copy(malEpisodes = null)
        val state =
            relatedState(
                related = listOf(RelatedAnime(anime, RelationType.Sequel)),
                entries = mapOf(anime.id to ListValues(ListStatus.Watching, 5, null, null, null)),
                airing = mapOf(anime.id to Airing(AiredCount.Exact(6), 12, null, Instant.EPOCH)),
                language = TitleLanguage.Romaji,
                session = SubScreenFixtures.session,
            )
        show { RelatedScreen(state, onEvent = {}, snackbars = snackbars) }
        compose.onNodeWithText("Watching\u00A0· 5\u00A0/\u00A012", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun synonymsCutAtTheLineCapEndInAnEllipsisAndNoneIsLeftOut() {
        show { DetailsScreen(SubScreenFixtures.details(SubScreenFixtures.manySynonyms), onEvent = {}, snackbars = snackbars) }
        val synonyms = SubScreenFixtures.manySynonyms.titles.synonyms
        val texts =
            synonyms.map { synonym ->
                compose.onNodeWithText(synonym, substring = true, useUnmergedTree = true).fetchSemanticsNode()
            }
        val last = texts.last()
        val layout =
            mutableListOf<TextLayoutResult>()
                .also {
                    last.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(
                        it,
                    )
                }.single()
        assertTrue(layout.isLineEllipsized(layout.lineCount - 1))
        assertTrue(texts.map { it.id }.toSet().size < synonyms.size)
    }

    @Test
    fun withEnglishTitlesDetailsShowTheRomajiTitle() {
        val state = SubScreenFixtures.details(FixtureAnime.exactToWatch, TitleLanguage.English)
        show { DetailsScreen(state, onEvent = {}, snackbars = snackbars) }
        compose.onNodeWithText("Romaji title", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(FixtureAnime.exactToWatch.titles.romaji, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("English title", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun oneStudioIsLabelledInTheSingular() {
        val details = FixtureDetails.full.copy(studios = listOf("Studio Hanamizuki"))
        show {
            DetailsScreen(
                detailsState(FixtureAnime.exactToWatch, details, TitleLanguage.Romaji, SubScreenFixtures.session, Fixtures.reference.clock),
                onEvent = {},
                snackbars = snackbars,
            )
        }
        compose.onNodeWithText("Studio", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Studios", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aRelatedRowShowsItsFactsAndStatusAndOpensTheTitle() {
        val events = mutableListOf<RelatedEvent>()
        show { RelatedScreen(SubScreenFixtures.related(FixtureAnime.premiereYear), onEvent = { events += it }, snackbars = snackbars) }
        compose.onNodeWithText("Prequel").assertIsDisplayed()
        compose.onNodeWithText("TV\u00A0· 2025", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Watching\u00A0· 12\u00A0/\u00A024", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(FixtureAnime.exactToWatch.titles.romaji, useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf(RelatedEvent.Open(FixtureAnime.exactToWatch.id), RelatedEvent.Back), events)
    }

    @Test
    fun detailsWithNoFactShowTheEmptyState() {
        show { DetailsScreen(DetailsState.Shown(emptyList(), SubScreenFixtures.session), onEvent = {}, snackbars = snackbars) }
        compose.onNodeWithText("MyAnimeList gives no details for this title").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp-xhdpi")
    fun theBroadcastAndDatesFollowTheLocale() {
        show { DetailsScreen(SubScreenFixtures.details(FixtureAnime.exactToWatch), onEvent = {}, snackbars = snackbars) }
        compose.onNodeWithText("По субботам в\u00A017:30", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("окт.", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("23\u00A0мин", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theSessionBannerShowsInEveryStateOnlyForAnExpiredSessionAndSendsSignIn() {
        val related: (Session) -> List<RelatedState> = {
            listOf(
                RelatedState.Loading(it),
                RelatedState.Failed(DataError.NoConnection, it),
                SubScreenFixtures.related(FixtureAnime.premiereYear, it),
            )
        }
        val details: (Session) -> List<DetailsState> = {
            listOf(
                DetailsState.Loading(it),
                DetailsState.Failed(DataError.NoConnection, it),
                SubScreenFixtures.details(FixtureAnime.exactToWatch).copy(session = it),
            )
        }
        var screen: @Composable () -> Unit by mutableStateOf({})
        show { screen() }
        for (session in listOf(SubScreenFixtures.session, Session.Expired(Fixtures.reference.session.signIn))) {
            val expired = session is Session.Expired
            related(session).forEach { state ->
                val events = mutableListOf<RelatedEvent>()
                screen = { RelatedScreen(state, onEvent = { events += it }, snackbars = snackbars) }
                assertBanner(expired)
                assertEquals(if (expired) listOf(RelatedEvent.SignIn) else emptyList<RelatedEvent>(), events)
            }
            details(session).forEach { state ->
                val events = mutableListOf<DetailsEvent>()
                screen = { DetailsScreen(state, onEvent = { events += it }, snackbars = snackbars) }
                assertBanner(expired)
                assertEquals(if (expired) listOf(DetailsEvent.SignIn) else emptyList<DetailsEvent>(), events)
            }
        }
    }

    private fun assertBanner(expired: Boolean) {
        if (expired) {
            compose.onNodeWithText("Session expired").assertIsDisplayed()
            compose.onNodeWithText("Sign in").performClick()
        } else {
            compose.onNodeWithText("Session expired").assertDoesNotExist()
        }
    }

    @Test
    fun aSubScreenDrawsTheShellsSnackbarHost() {
        show {
            LaunchedEffect(Unit) { snackbars.showSnackbar("Could not save") }
            DetailsScreen(SubScreenFixtures.details(FixtureAnime.exactToWatch), onEvent = {}, snackbars = snackbars)
        }
        compose.onNodeWithText("Could not save").assertIsDisplayed()
    }
}
