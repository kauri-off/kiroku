package io.github.kauri_off.kiroku.title

import android.graphics.Color
import android.os.Build
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.core.view.WindowCompat
import androidx.test.espresso.Espresso
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.link.Link
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.TitlePicture
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.design.component.LocalRouteImages
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.manga.MangaTitleKey
import io.github.kauri_off.kiroku.manga.MangaTitleRoute
import io.github.kauri_off.kiroku.manga.MangaTitleSummary
import io.github.kauri_off.kiroku.manga.mangaFixture
import io.github.kauri_off.kiroku.screenshots.RemoteImages
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.linkedPage
import io.github.kauri_off.kiroku.shell.openLink
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
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
import org.robolectric.shadows.ShadowDialog
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
        val demo = TestGraph.over(scope)
        val graph =
            object : AppGraph by demo {
                override val anime =
                    object : AnimeRepository by demo.anime {
                        override fun observeDetails(id: AnimeId) =
                            demo.anime.observeDetails(id).map { it?.copy(pictures = listOf(TitlePicture(EXTRA, null))) }
                    }
            }
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
        val details =
            mangaFixture.copy(
                synopsis = checkNotNull(mangaFixture.synopsis).repeat(3),
                pictures = listOf(TitlePicture(EXTRA, null)),
            )
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
    fun swipeAndButtonsBrowseInOrderAndRetryOnlyTheFailedPage() {
        val requested = CopyOnWriteArrayList<Pair<Any, CachePolicy>>()
        val loader =
            ImageLoader
                .Builder(compose.activity)
                .components {
                    add(
                        Interceptor { chain ->
                            val request = chain.request
                            requested += request.data to request.diskCachePolicy
                            if (request.data == EXTRA_LARGE || request.data == EXTRA && request.diskCachePolicy == CachePolicy.ENABLED) {
                                ErrorResult(null, request, IOException())
                            } else {
                                coverResult(request)
                            }
                        },
                    )
                }.build()
        val pictures = listOf(TitlePicture(EXTRA, EXTRA_LARGE), TitlePicture(LAST, null))
        show(loader) { TitleCoverDialog("Sample", REGULAR, LARGE, pictures) {} }
        waitForCover("Sample")
        compose.onNodeWithText("Previous").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Cover of Sample").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2\u00A0/\u00A03").assertIsDisplayed()
        val error = hasText("Could not load this image")
        compose.waitUntil { compose.onAllNodes(error).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Retry").performClick()
        val extra = hasContentDescription("Image\u00A02\u00A0of\u00A03\u00A0for Sample")
        compose.waitUntil { compose.onAllNodes(extra).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(extra).assertIsDisplayed()
        assertTrue((EXTRA_LARGE to CachePolicy.ENABLED) in requested)
        assertTrue((EXTRA to CachePolicy.ENABLED) in requested)
        assertTrue((EXTRA to CachePolicy.WRITE_ONLY) in requested)
        assertTrue(requested.none { (url, policy) -> url == LARGE && policy == CachePolicy.WRITE_ONLY })

        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("3\u00A0/\u00A03").assertIsDisplayed()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Previous").performClick()
        compose.onNodeWithText("2\u00A0/\u00A03").assertIsDisplayed()
    }

    @Test
    fun anExtraStaysSelectedWhenItMovesAndGainsALargeSource() {
        val pictures = mutableStateOf(listOf(TitlePicture(EXTRA, null), TitlePicture(LAST, null)))
        val releaseLarge = CompletableDeferred<Unit>()
        val requested = CopyOnWriteArrayList<Any>()
        val loader =
            ImageLoader
                .Builder(compose.activity)
                .components {
                    add(
                        Interceptor { chain ->
                            requested += chain.request.data
                            if (chain.request.data == EXTRA_LARGE) releaseLarge.await()
                            coverResult(chain.request)
                        },
                    )
                }.build()
        show(loader) { TitleCoverDialog("Sample", REGULAR, LARGE, pictures.value) {} }
        compose.onNodeWithText("Next").performClick()
        val before = hasContentDescription("Image\u00A02\u00A0of\u00A03\u00A0for Sample")
        compose.waitUntil { compose.onAllNodes(before).fetchSemanticsNodes().isNotEmpty() }

        compose.runOnIdle { pictures.value = listOf(TitlePicture(LAST, null), TitlePicture(EXTRA, EXTRA_LARGE)) }
        compose.onNodeWithText("3\u00A0/\u00A03").assertIsDisplayed()
        compose.waitUntil { EXTRA_LARGE in requested }
        val after = hasContentDescription("Image\u00A03\u00A0of\u00A03\u00A0for Sample")
        compose.onNode(after).assertDoesNotExist()
        releaseLarge.complete(Unit)
        compose.waitUntil { compose.onAllNodes(after).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(after).assertIsDisplayed()
        assertTrue(EXTRA in requested)
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
        val requested = CopyOnWriteArrayList<Pair<Any, CachePolicy>>()
        val loader =
            ImageLoader
                .Builder(compose.activity)
                .components {
                    add(
                        Interceptor { chain ->
                            requested += chain.request.data to chain.request.diskCachePolicy
                            if (first.complete(Unit)) ErrorResult(null, chain.request, IOException()) else coverResult(chain.request)
                        },
                    )
                }.build()
        show(loader) { TitleCoverDialog("Sample", LARGE, LARGE) {} }
        compose.waitUntil { compose.onAllNodes(hasText("Could not load the cover")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(LARGE to CachePolicy.ENABLED), requested.toList())
        compose.onNode(hasText("Retry")).performClick()
        waitForCover("Sample")
        assertEquals(listOf(LARGE to CachePolicy.ENABLED, LARGE to CachePolicy.WRITE_ONLY), requested.toList())
    }

    @Test
    fun aCoverThatDisappearsStaysClosedWhenItReturns() {
        val url = mutableStateOf<String?>(LARGE)
        val open = mutableStateOf(true)
        show {
            if (open.value) TitleCoverDialog("Sample", url.value, null) { open.value = false }
        }
        waitForCover("Sample")
        compose.runOnIdle { url.value = null }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { url.value = LARGE }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun leavingTheTopEntryDismissesTheWindowAndClearsOpenState() {
        val onTop = mutableStateOf(true)
        val open = mutableStateOf(true)
        show {
            CompositionLocalProvider(LocalOnTop provides onTop.value) {
                if (open.value) TitleCoverDialog("Sample", LARGE, null) { open.value = false }
            }
        }
        waitForCover("Sample")
        compose.runOnIdle { onTop.value = false }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { onTop.value = true }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [30, 35])
    @Suppress("DEPRECATION")
    fun theDialogSystemBarIconsFollowTheAppTheme() {
        val dark = mutableStateOf(false)
        show {
            KirokuTheme(dark = dark.value, dynamicColor = false) {
                TitleCoverDialog("Sample", LARGE, null) {}
            }
        }
        waitForCover("Sample")

        fun assertLightAppearance(expected: Boolean) {
            compose.runOnIdle {
                val window = checkNotNull(ShadowDialog.getLatestDialog()?.window)
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                assertEquals(expected, controller.isAppearanceLightStatusBars)
                assertEquals(expected, controller.isAppearanceLightNavigationBars)
                if (Build.VERSION.SDK_INT == 30) {
                    assertEquals(Color.TRANSPARENT, window.navigationBarColor)
                    assertTrue(window.isNavigationBarContrastEnforced)
                }
            }
        }
        assertLightAppearance(true)
        compose.runOnIdle { dark.value = true }
        assertLightAppearance(false)
    }

    @Test
    fun replacingATitleAtTheSameDepthDismissesItsCoverDuringTheTransition() {
        val graph = TestGraph.over(scope)
        val first = Link.Title(FixtureAnime.exactToWatch.id)
        val second = Link.Title(FixtureAnime.prequel.id)
        lateinit var navigator: ShellNavigator
        show {
            navigator = rememberShellNavigator(linkedPage(first))
            AppRoute(graph, navigator)
        }
        val cover = hasContentDescription("Enlarge cover")
        compose.waitUntil { compose.onAllNodes(cover).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(cover).performClick()
        compose.onNode(isDialog()).assertExists()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { navigator.openLink(second) }
            repeat(6) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            compose.onNode(isDialog()).assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
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
            val next = hasText("Next")
            compose.waitUntil { compose.onAllNodes(next).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(next).performClick()
            compose.onNodeWithText("2\u00A0/\u00A02").assertIsDisplayed()
            if (opening == 0) compose.onNodeWithContentDescription("Close gallery").performClick() else Espresso.pressBack()
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
        const val EXTRA = "https://example.invalid/cover/extra.jpg"
        const val EXTRA_LARGE = "https://example.invalid/cover/extra-large.jpg"
        const val LAST = "https://example.invalid/cover/last.jpg"
        const val REGULAR = "https://example.invalid/cover/regular.jpg"
        const val LARGE = "https://example.invalid/cover/large.jpg"
    }
}
