package io.github.kauri_off.kiroku.title

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import androidx.compose.ui.test.swipeRight
import androidx.core.view.WindowCompat
import androidx.test.espresso.Espresso
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileNotFoundException
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
    fun swipingBrowsesInOrderAndRetryOnlyReloadsTheFailedPage() {
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
        compose.onNodeWithText("Previous").assertDoesNotExist()
        compose.onNodeWithText("Next").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cover of Sample").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2\u00A0/\u00A03").assertIsDisplayed()
        val error = hasText("Could not load this image")
        compose.waitUntil { compose.onAllNodes(error).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Download image").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Share image").assertIsNotEnabled()
        compose.onNodeWithText("Retry").performClick()
        val extra = hasContentDescription("Image\u00A02\u00A0of\u00A03\u00A0for Sample")
        compose.waitUntil { compose.onAllNodes(extra).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(extra).assertIsDisplayed()
        assertTrue((EXTRA_LARGE to CachePolicy.ENABLED) in requested)
        assertTrue((EXTRA to CachePolicy.ENABLED) in requested)
        assertTrue((EXTRA to CachePolicy.WRITE_ONLY) in requested)
        assertTrue(requested.none { (url, policy) -> url == LARGE && policy == CachePolicy.WRITE_ONLY })

        compose.onNode(extra).performTouchInput { swipeLeft() }
        compose.onNodeWithText("3\u00A0/\u00A03").assertIsDisplayed()
        val last = hasContentDescription("Image\u00A03\u00A0of\u00A03\u00A0for Sample")
        compose.waitUntil { compose.onAllNodes(last).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(last).performTouchInput { swipeRight() }
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
        waitForCover("Sample")
        compose.onNodeWithContentDescription("Cover of Sample").performTouchInput { swipeLeft() }
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
    fun downloadAndShareUseTheSettledLoadedImageEvenAfterASwipeAndFallback() {
        val storage = registerStorage()
        val releaseLarge = CompletableDeferred<Unit>()
        val requested = CopyOnWriteArrayList<Any>()
        val loader =
            exportLoader { request ->
                requested += request.data
                when (request.data) {
                    EXTRA_LARGE -> {
                        releaseLarge.await()
                        ErrorResult(null, request, IOException())
                    }
                    EXTRA -> coverResult(request, Color.GREEN)
                    else -> coverResult(request, Color.RED)
                }
            }
        show(loader) { TitleCoverDialog("Sample", REGULAR, LARGE, listOf(TitlePicture(EXTRA, EXTRA_LARGE))) {} }
        waitForCover("Sample")
        saveAndShare(Color.RED, storage)

        compose.onNodeWithContentDescription("Cover of Sample").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2\u00A0/\u00A02").assertIsDisplayed()
        compose.onNodeWithContentDescription("Download image").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Share image").assertIsNotEnabled()
        releaseLarge.complete(Unit)
        val extra = hasContentDescription("Image\u00A02\u00A0of\u00A02\u00A0for Sample")
        compose.waitUntil { compose.onAllNodes(extra).fetchSemanticsNodes().isNotEmpty() }
        saveAndShare(Color.GREEN, storage)
        assertEquals(listOf(LARGE, EXTRA_LARGE, EXTRA), requested.toList())
    }

    @Test
    fun singleCoverShowsAFailedSaveAndCanBeSavedAgain() {
        val storage = registerStorage()
        storage.rejectPublish = true
        show { TitleCoverDialog("Sample", REGULAR, LARGE) {} }
        waitForCover("Sample")
        compose.onNodeWithContentDescription("Download image").performClick()
        waitForMessage("Could not save the image")
        assertEquals(1, storage.deletions)
        assertTrue(!storage.file.exists())
        storage.rejectPublish = false
        saveAndShare(RemoteImages.BLOCK, storage)
    }

    @Test
    fun failedShareShowsFeedbackAndReenablesTheActions() {
        val path = File(compose.activity.cacheDir, "cover-shares")
        path.writeText("Unavailable")
        show { TitleCoverDialog("Sample", REGULAR, LARGE) {} }
        waitForCover("Sample")
        val share = compose.onNodeWithContentDescription("Share image")
        share.performClick()
        waitForMessage("Could not share the image")
        share.assertIsEnabled()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
    }

    @Test
    @Config(sdk = [28])
    fun legacyDownloadUsesTheChosenFolderAndCreatesNothingWhenCancelled() {
        val storage = registerStorage(authority = "test.documents")
        show { TitleCoverDialog("Sample", REGULAR, LARGE) {} }
        waitForCover("Sample")
        val download = compose.onNodeWithContentDescription("Download image")
        val activity = shadowOf(compose.activity)
        download.performClick()
        val cancelled = activity.nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, cancelled.action)
        compose.runOnIdle { activity.receiveResult(cancelled, Activity.RESULT_CANCELED, null) }
        download.assertIsEnabled()
        assertNull(storage.name)

        download.performClick()
        val picker = activity.nextStartedActivityForResult.intent
        val tree = DocumentsContract.buildTreeDocumentUri("test.documents", "Pictures")
        compose.runOnIdle { activity.receiveResult(picker, Activity.RESULT_OK, Intent().setData(tree)) }
        waitForMessage("Image saved")
        assertEquals("image/png", storage.mime)
        assertTrue(checkNotNull(storage.name).endsWith(".png"))
        assertEquals(RemoteImages.BLOCK, BitmapFactory.decodeFile(storage.file.path).getPixel(0, 0))
    }

    private fun saveAndShare(
        color: Int,
        storage: CoverStorage,
    ) {
        val savedBefore = storage.saved
        compose.onNodeWithContentDescription("Download image").performClick()
        compose.waitUntil { storage.saved > savedBefore }
        waitForMessage("Image saved")
        assertEquals("image/png", storage.mime)
        assertTrue(checkNotNull(storage.name).endsWith(".png"))
        assertEquals("Pictures/Kiroku", storage.path)
        assertEquals(0, storage.pending)
        assertEquals(color, BitmapFactory.decodeFile(storage.file.path).getPixel(0, 0))
        val authority = "${compose.activity.packageName}.cover-shares"
        checkNotNull(ShadowContentResolver.getProvider(Uri.parse("content://$authority")))
        compose.onNodeWithContentDescription("Share image").performClick()
        val activity = shadowOf(compose.activity)
        compose.waitUntil { compose.runOnIdle { activity.peekNextStartedActivity() != null } }
        val chooser = checkNotNull(activity.nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))

        @Suppress("DEPRECATION")
        val uri = checkNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertEquals("content", uri.scheme)
        assertEquals(uri, checkNotNull(send.clipData).getItemAt(0).uri)
        assertEquals(uri, checkNotNull(chooser.clipData).getItemAt(0).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
        checkNotNull(compose.activity.contentResolver.openInputStream(uri)).use {
            assertEquals(color, BitmapFactory.decodeStream(it).getPixel(0, 0))
        }
    }

    private fun waitForMessage(text: String) {
        compose.waitUntil { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun registerStorage(authority: String = "media"): CoverStorage {
        val file = File(compose.activity.cacheDir, "test-saved-cover.png")
        return CoverStorage(file, Uri.parse("content://$authority/image")).also {
            val info = ProviderInfo().apply { this.authority = authority }
            it.attachInfo(compose.activity, info)
            ShadowContentResolver.registerProviderInternal(authority, it)
        }
    }

    private fun exportLoader(load: suspend (ImageRequest) -> ImageResult) =
        ImageLoader
            .Builder(compose.activity)
            .components { add(Interceptor { chain -> load(chain.request) }) }
            .build()

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
            image.performTouchInput { swipeLeft() }
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

    private fun coverResult(
        request: ImageRequest,
        color: Int = RemoteImages.BLOCK,
    ) = SuccessResult(
        ColorImage(color, width = RemoteImages.POSTER_WIDTH, height = RemoteImages.POSTER_HEIGHT),
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

private class CoverStorage(
    val file: File,
    private val imageUri: Uri,
) : ContentProvider() {
    var rejectPublish = false
    var deletions = 0
    var name: String? = null
    var mime: String? = null
    var path: String? = null
    var pending = -1

    @Volatile
    var saved = 0
        private set

    override fun onCreate() = true

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri {
        val row = checkNotNull(values)
        name = row.getAsString(MediaStore.Images.Media.DISPLAY_NAME)
        mime = row.getAsString(MediaStore.Images.Media.MIME_TYPE)
        path = row.getAsString(MediaStore.Images.Media.RELATIVE_PATH)
        pending = row.getAsInteger(MediaStore.Images.Media.IS_PENDING)
        return imageUri
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        if (rejectPublish) return 0
        assertEquals(1, pending)
        assertTrue(file.length() > 0)
        pending = checkNotNull(values).getAsInteger(MediaStore.Images.Media.IS_PENDING)
        saved++
        return 1
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        deletions++
        return if (file.delete()) 1 else 0
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (uri != imageUri) throw FileNotFoundException()
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
        )
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        assertEquals("android:createDocument", method)
        val values = checkNotNull(extras)
        mime = values.getString("mime_type")
        name = values.getString("_display_name")
        return Bundle().apply { putParcelable("uri", imageUri) }
    }

    override fun getType(uri: Uri): String? = mime

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null
}
