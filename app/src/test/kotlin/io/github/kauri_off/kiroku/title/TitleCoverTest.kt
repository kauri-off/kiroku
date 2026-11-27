package io.github.kauri_off.kiroku.title

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.espresso.Espresso
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.design.component.LocalRouteImages
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.manga.MangaTitleKey
import io.github.kauri_off.kiroku.manga.MangaTitleRoute
import io.github.kauri_off.kiroku.manga.MangaTitleSummary
import io.github.kauri_off.kiroku.manga.mangaFixture
import io.github.kauri_off.kiroku.screenshots.RemoteImages
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h600dp-xhdpi")
class TitleCoverTest {
    private val compose = composeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @get:Rule
    val rule = registered(compose) { scope.cancel() }

    @Test
    fun animeReturnsToTheSameScrollPositionAfterCloseAndBack() {
        val graph = TestGraph.over(scope)
        val anime = FixtureAnime.exactToWatch
        val links = TitleLinks({ error("Left the title") }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, { _, _ -> }, {})
        val messages = ShellMessages(scope, graph.list)
        show {
            TitleRoute(TitleKey(anime.id.value), graph, messages, SnackbarHostState(), links)
        }
        assertReturnsToTitle(anime.titles.romaji)
    }

    @Test
    fun mangaReturnsToTheSameScrollPositionAfterCloseAndBack() {
        val demo = TestGraph.over(scope)
        val details = mangaFixture.copy(synopsis = checkNotNull(mangaFixture.synopsis).repeat(3))
        val graph =
            object : AppGraph by demo {
                override val manga =
                    object : MangaRepository {
                        override fun observeOwner() = demo.session.observeSession().map { SessionOwner(it.owner) }

                        override suspend fun stored(
                            id: MangaId,
                            owner: SessionOwner,
                        ) = Outcome.Success(StoredManga(details, demo.clock.instant()))

                        override suspend fun refresh(
                            id: MangaId,
                            owner: SessionOwner,
                            canDispatch: () -> Boolean,
                        ) = if (canDispatch()) stored(id, owner) else null
                    }
            }
        val key = MangaTitleKey(MangaTitleSummary(details, TitleLanguage.Romaji))
        val messages = ShellMessages(scope, graph.list)
        show {
            MangaTitleRoute(key, graph, messages, SnackbarHostState(), onBack = { error("Left the title") }, onSignIn = {})
        }
        assertReturnsToTitle(details.titles.romaji)
    }

    @Test
    fun theLargeImageLoadsFirstAndFallsBackToTheRegularImage() {
        val releaseLarge = CompletableDeferred<Unit>()
        val requested = CopyOnWriteArrayList<Any>()
        val loader =
            ImageLoader
                .Builder(compose.activity)
                .components {
                    add(
                        Interceptor { chain ->
                            requested += chain.request.data
                            if (chain.request.data == LARGE) {
                                releaseLarge.await()
                                ErrorResult(null, chain.request, IOException())
                            } else {
                                coverResult(chain.request)
                            }
                        },
                    )
                }.build()
        show(loader) { TitleCoverDialog("Sample", REGULAR, LARGE) {} }
        compose.onNodeWithContentDescription("Loading").assertExists()
        compose.waitUntil { requested.isNotEmpty() }
        assertEquals(listOf(LARGE), requested.toList())
        releaseLarge.complete(Unit)
        waitForCover("Sample")
        assertEquals(listOf(LARGE, REGULAR), requested.toList())
    }

    @Test
    fun anErrorOffersRetryAndDoesNotRequestADuplicateUrl() {
        val first = CompletableDeferred<Unit>()
        val requested = CopyOnWriteArrayList<Any>()
        val loader =
            ImageLoader
                .Builder(compose.activity)
                .components {
                    add(
                        Interceptor { chain ->
                            requested += chain.request.data
                            if (first.complete(Unit)) ErrorResult(null, chain.request, IOException()) else coverResult(chain.request)
                        },
                    )
                }.build()
        show(loader) { TitleCoverDialog("Sample", LARGE, LARGE) {} }
        compose.waitUntil { compose.onAllNodes(hasText("Could not load the cover")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(LARGE), requested.toList())
        compose.onNode(hasText("Retry")).performClick()
        waitForCover("Sample")
        assertEquals(listOf(LARGE, LARGE), requested.toList())
    }

    @Test
    fun aMissingCoverDoesNotExposeAnActionOrOpenADialog() {
        val anime = FixtureAnime.exactToWatch.copy(posterUrl = null, largePosterUrl = "")
        show { TitleInShell(TitleFixtures.of(anime).copy(coverOpen = true), {}) }
        compose.onNodeWithContentDescription("Enlarge cover").assertDoesNotExist()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    private fun assertReturnsToTitle(title: String) {
        val hero = compose.onNodeWithContentDescription("Enlarge cover")
        val pageTitle = compose.onNode(isHeading() and hasText(title))
        val scroller = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        compose.waitUntil { compose.onAllNodes(isHeading() and hasText(title)).fetchSemanticsNodes().isNotEmpty() }
        scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 60f) }
        val position = pageTitle.fetchSemanticsNode().positionInRoot
        val axis = scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val offset = axis.value()
        assertTrue(offset > 0)
        val heroHeight = hero.fetchSemanticsNode().size.height

        repeat(2) { opening ->
            hero.assertIsDisplayed().performClick()
            waitForCover(title)
            val image = compose.onNodeWithContentDescription("Cover of $title")
            assertTrue(image.fetchSemanticsNode().size.height > heroHeight)
            if (opening == 0) compose.onNodeWithContentDescription("Close cover").performClick() else Espresso.pressBack()
            compose.onNode(isDialog()).assertDoesNotExist()
            pageTitle.assertIsDisplayed()
            assertEquals(position, pageTitle.fetchSemanticsNode().positionInRoot)
            assertEquals(offset, scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
        }
    }

    private fun waitForCover(title: String) {
        val description = hasContentDescription("Cover of $title")
        compose.waitUntil { compose.onAllNodes(description).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(description).assertIsDisplayed()
    }

    private fun show(
        loader: ImageLoader =
            ImageLoader
                .Builder(compose.activity)
                .components { add(Interceptor { chain -> coverResult(chain.request) }) }
                .build(),
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                CompositionLocalProvider(LocalRouteImages provides loader, content = content)
            }
        }
    }

    private fun coverResult(request: ImageRequest) =
        SuccessResult(
            ColorImage(RemoteImages.BLOCK, width = RemoteImages.POSTER_WIDTH, height = RemoteImages.POSTER_HEIGHT),
            request,
            DataSource.MEMORY,
        )

    private companion object {
        const val REGULAR = "https://example.invalid/cover/regular.jpg"
        const val LARGE = "https://example.invalid/cover/large.jpg"
    }
}
