package io.github.kauri_off.kiroku.manga

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.link.readLink
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.link.AppLinks
import io.github.kauri_off.kiroku.link.LinksOwner
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

open class StartedByMangaLinkApplication :
    Application(),
    AppGraphOwner,
    LinksOwner {
    protected val test by lazy { TestGraph(Demo(Fixtures.reference, CoroutineScope(SupervisorJob() + Dispatchers.Main))) }
    override val links = AppLinks()
    val reads = mutableListOf<MangaId>()

    override val graph: AppGraph by lazy {
        object : AppGraph by test {
            override val manga =
                object : MangaRepository {
                    override fun observeOwner() = flowOf(SessionOwner(null))

                    private var stored: StoredManga? = null

                    override suspend fun stored(
                        id: MangaId,
                        owner: SessionOwner,
                    ) = Outcome.Success(stored)

                    override suspend fun refresh(
                        id: MangaId,
                        owner: SessionOwner,
                    ): Outcome<StoredManga> {
                        reads += id
                        if (reads.size == 1) return Outcome.Failure(DataError.NotFound)
                        return Outcome.Success(StoredManga(mangaFixture, clock.instant()).also { stored = it })
                    }
                }
        }
    }

    override fun onCreate() {
        super.onCreate()
        links.opened(checkNotNull(readLink("https://myanimelist.net/manga/${mangaFixture.id.value}/Paper_Observatory")))
    }
}

class StartedByUnavailableMangaLinkApplication : StartedByMangaLinkApplication() {
    override val graph: AppGraph get() = test
}

@RunWith(RobolectricTestRunner::class)
@Config(application = StartedByMangaLinkApplication::class, qualifiers = "en-w411dp-h891dp")
class MangaTitleLinkRouteTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = RuntimeEnvironment.getApplication() as StartedByMangaLinkApplication

    @Test
    fun `cold manga link offers retry, restores the page, and goes back to the List`() {
        compose.onNodeWithText("This title is not on MyAnimeList").assertExists()
        compose.onNodeWithText("Retry").performClick()
        showsManga()
        assertEquals(listOf(mangaFixture.id, mangaFixture.id), app.reads)

        compose.activityRule.scenario.recreate()
        showsManga()
        assertEquals(listOf(mangaFixture.id, mangaFixture.id), app.reads)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("My list").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `same numbered anime link still opens anime and Back in the manga bar returns to the List`() {
        compose.onNodeWithText("Retry").performClick()
        showsManga()
        val anime = FixtureAnime.exactToWatch
        app.links.opened(checkNotNull(readLink("https://myanimelist.net/anime/${anime.id.value}")))
        compose.onNode(isHeading() and hasText(anime.titles.romaji)).assertExists()
        compose.onNodeWithContentDescription("Episodes watched").assertExists()

        app.links.opened(checkNotNull(readLink("https://myanimelist.net/manga/${mangaFixture.id.value}")))
        showsManga()
        assertEquals(listOf(mangaFixture.id, mangaFixture.id), app.reads)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("My list").assertExists()
    }

    @Test
    @Config(application = StartedByUnavailableMangaLinkApplication::class)
    fun `a Demo manga link has no retry action`() {
        compose.onNodeWithText("Manga pages are unavailable in this mode").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    private fun showsManga() {
        compose.onNode(isHeading() and hasText(mangaFixture.titles.romaji)).assertExists()
        compose.onNodeWithText("162\u00a0chapters", substring = true).assertExists()
        compose.onNodeWithContentDescription("Episodes watched").assertDoesNotExist()
    }
}
