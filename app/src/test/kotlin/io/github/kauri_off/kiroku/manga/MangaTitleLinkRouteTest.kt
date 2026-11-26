package io.github.kauri_off.kiroku.manga

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.link.readLink
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListEditResult
import io.github.kauri_off.kiroku.core.repository.MangaListEditor
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.core.repository.readingStatus
import io.github.kauri_off.kiroku.link.AppLinks
import io.github.kauri_off.kiroku.link.LinksOwner
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock

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
                    override fun observeOwner() = test.session.observeSession().map { SessionOwner(it.owner) }

                    private var stored: StoredManga? = null

                    override suspend fun stored(
                        id: MangaId,
                        owner: SessionOwner,
                    ) = Outcome.Success(stored)

                    override suspend fun refresh(
                        id: MangaId,
                        owner: SessionOwner,
                        canDispatch: () -> Boolean,
                    ): Outcome<StoredManga>? {
                        if (!canDispatch()) return null
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

open class StartedByOwnedMangaLinkApplication : StartedByMangaLinkApplication() {
    internal open val personalManga by lazy { MangaAddRouteList(test.clock) }

    override val graph: AppGraph by lazy {
        object : AppGraph by test {
            override val manga =
                object : MangaRepository {
                    override fun observeOwner() = test.session.observeSession().map { SessionOwner(it.owner) }

                    override suspend fun stored(
                        id: MangaId,
                        owner: SessionOwner,
                    ) = Outcome.Success(StoredManga(mangaFixture, clock.instant()))

                    override suspend fun refresh(
                        id: MangaId,
                        owner: SessionOwner,
                        canDispatch: () -> Boolean,
                    ) = if (canDispatch()) stored(id, owner) else null
                }
            override val mangaListEditor = personalManga
            override val mangaList = personalManga
        }
    }
}

class StartedByUnknownMangaLinkApplication : StartedByOwnedMangaLinkApplication() {
    internal override val personalManga by lazy {
        MangaAddRouteList(test.clock, known = false).apply { readError = DataError.ServerError }
    }
}

internal class MangaAddRouteList(
    private val clock: Clock,
    known: Boolean = true,
) : MangaListEditor {
    private val entries = MutableStateFlow<List<MangaListEntry>?>(emptyList<MangaListEntry>().takeIf { known })
    var remote = emptyList<MangaListEntry>()
    var readError: DataError? = null
    var storedError: DataError? = null
    var addReadBackError: DataError? = null
    private var dirty = false
    var allowRead = CompletableDeferred(Unit)
    val membershipReads = mutableListOf<SignInId>()
    val additions = mutableListOf<Pair<SignInId, MangaId>>()

    override fun observe(owner: SignInId) = entries.map { Outcome.Success(snapshot(owner, it)) }

    override suspend fun stored(owner: SignInId): Outcome<MangaListSnapshot?> =
        storedError?.let { Outcome.Failure(it) } ?: Outcome.Success(snapshot(owner, entries.value))

    override suspend fun refresh(
        owner: SignInId,
        canDispatch: () -> Boolean,
    ): Outcome<MangaListSnapshot>? {
        allowRead.await()
        if (!canDispatch()) return null
        membershipReads += owner
        readError?.let { return Outcome.Failure(it) }
        dirty = false
        entries.value = remote
        return Outcome.Success(MangaListSnapshot(owner, remote, clock.instant()))
    }

    override suspend fun addPlanned(
        owner: SignInId,
        id: MangaId,
    ): MangaListEditResult {
        check(id == mangaFixture.id)
        check(remote.none { it.id == id })
        additions += owner to id
        val added = mangaEntry.copy(status = MangaListStatus.PlanToRead.readingStatus)
        remote = remote + added
        addReadBackError?.let {
            dirty = true
            return MangaListEditResult.Uncertain(it, needsRefresh = true)
        }
        entries.value = remote
        return MangaListEditResult.Confirmed(added)
    }

    override suspend fun edit(
        owner: SignInId,
        id: MangaId,
        changes: MangaListEdit,
        retryFrom: MangaListEntry?,
    ) = error("Unexpected edit")

    override suspend fun remove(
        owner: SignInId,
        baseline: MangaListEntry,
        retry: Boolean,
    ) = error("Unexpected removal")

    private fun snapshot(
        owner: SignInId,
        entries: List<MangaListEntry>?,
    ) = entries?.let { MangaListSnapshot(owner, it, clock.instant().takeUnless { dirty }) }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = StartedByMangaLinkApplication::class, qualifiers = "en-w411dp-h891dp")
class MangaTitleLinkRouteTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = RuntimeEnvironment.getApplication() as StartedByMangaLinkApplication

    @Test
    fun `public manga shows score rank and members in the anime order without list ownership`() {
        compose.onNodeWithText("Retry").performClick()
        val score = compose.onNodeWithText("MAL score").fetchSemanticsNode().boundsInRoot
        val rank = compose.onNodeWithText("Rank").fetchSemanticsNode().boundsInRoot
        val members = compose.onNodeWithText("Members").fetchSemanticsNode().boundsInRoot
        assertTrue(score.left < rank.left && rank.left < members.left)
        compose.onNodeWithText("9.16").assertExists()
        compose.onNodeWithText("#18").assertExists()
        compose.onNodeWithText("299,750").assertExists()
        compose.onNode(hasText("Reading") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("Add to list").assertDoesNotExist()
    }

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

    @Test
    @Config(application = StartedByOwnedMangaLinkApplication::class)
    fun `pull on a linked title refreshes a complete cached absence and then presence`() {
        val owned = (app as StartedByOwnedMangaLinkApplication).personalManga
        showsManga()
        val reading = compose.onNode(hasText("Reading") and hasClickAction())
        reading.assertDoesNotExist()
        assertTrue(owned.membershipReads.isEmpty())
        val content = compose.onNode(hasScrollAction() and hasAnyDescendant(isHeading() and hasText(mangaFixture.titles.romaji)))
        compose.runOnUiThread { owned.remote = listOf(mangaEntry) }
        content.performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        reading.assertExists()

        compose.runOnUiThread { owned.remote = emptyList() }
        content.performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        reading.assertDoesNotExist()
        assertEquals(2, owned.membershipReads.size)
        assertEquals(1, owned.membershipReads.distinct().size)
    }

    @Test
    @Config(application = StartedByUnknownMangaLinkApplication::class)
    fun `a linked title waits for confirmed absence and Change opens its manga editor`() {
        val list = (app as StartedByUnknownMangaLinkApplication).personalManga
        showsManga()
        val add = compose.onNodeWithText("Add to list")
        compose.onNodeWithText("Could not check your list").assertExists()
        add.assertDoesNotExist()
        compose.runOnUiThread {
            list.readError = null
            list.allowRead = CompletableDeferred()
        }
        compose.onNodeWithText("Refresh list").performClick()
        add.assertDoesNotExist()
        compose.runOnUiThread { list.allowRead.complete(Unit) }
        add.performClick()
        compose.onNode(hasText("Planned") and hasClickAction()).assertExists()
        add.assertDoesNotExist()
        compose.onNodeWithText("Added to Planned").assertExists()
        compose.onNodeWithText("Change").performClick()
        compose.onNodeWithText("Save").assertExists()
        compose.onNodeWithContentDescription("Chapters read").assertExists()
        assertEquals(listOf(mangaFixture.id), list.additions.map { it.second })
        assertEquals(2, list.membershipReads.size)
    }

    @Test
    @Config(application = StartedByOwnedMangaLinkApplication::class)
    fun `refresh remains on the title when the unconfirmed add snackbar expires`() {
        val list = (app as StartedByOwnedMangaLinkApplication).personalManga
        compose.runOnUiThread { list.addReadBackError = DataError.ServerError }
        compose.onNodeWithText("Add to list").performClick()
        compose.onAllNodesWithText("Could not confirm save").assertCountEquals(2)
        compose.mainClock.advanceTimeBy(12_000)
        compose.onAllNodesWithText("Could not confirm save").assertCountEquals(1)
        compose.onNodeWithText("Refresh list").performClick()
        compose.onNode(hasText("Planned") and hasClickAction()).assertExists()
        assertEquals(1, list.additions.size)
        assertEquals(list.additions.map { it.first }, list.membershipReads)
    }

    @Test
    @Config(application = StartedByUnknownMangaLinkApplication::class)
    fun `successful download with unreadable storage still offers Refresh list`() {
        val list = (app as StartedByUnknownMangaLinkApplication).personalManga
        val refresh = compose.onNodeWithText("Refresh list")
        refresh.assertExists()
        compose.runOnUiThread {
            list.readError = null
            list.storedError = DataError.StorageError
        }
        refresh.performClick()
        compose.onNodeWithText("Could not check your list").assertExists()
        compose.onNodeWithText("Add to list").assertDoesNotExist()
        compose.runOnUiThread { list.storedError = null }
        refresh.performClick()
        compose.onNodeWithText("Add to list").assertExists()
    }

    private fun showsManga() {
        compose.onNode(isHeading() and hasText(mangaFixture.titles.romaji)).assertExists()
        compose.onNodeWithText("162\u00a0chapters", substring = true).assertExists()
        compose.onNodeWithContentDescription("Episodes watched").assertDoesNotExist()
    }
}
