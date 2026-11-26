package io.github.kauri_off.kiroku.sandbox

import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.link.Link
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaListReader
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.link.appLinks
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.scenario.WriteFault
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.KeysInMemory
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SandboxMangaRouteTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    @get:Rule(order = 0)
    val keystore =
        object : ExternalResource() {
            override fun before() = KeysInMemory.install()

            override fun after() = KeysInMemory.uninstall()
        }

    @get:Rule(order = 1)
    val connections = SandboxAppTest.NoConnection()

    @get:Rule(order = 1)
    val network = DeviceOnline()

    @get:Rule(order = 2)
    val source = StartsOn(DataSource.Sandbox)

    @get:Rule(order = 2)
    val time = MovedClock()

    @get:Rule(order = 3)
    val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = RuntimeEnvironment.getApplication().appGraph as SandboxGraph
    private val manga: MangaListReader get() = graph.mangaList

    private fun <T> waited(block: suspend () -> T): T = runBlocking { withTimeout(WAIT) { block() } }

    private fun shows(
        text: String,
        substring: Boolean = false,
    ) = compose.waitUntil(WAIT) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private val readingStatus get() = compose.onNode(hasText("Reading") and hasClickAction())

    private fun openList() {
        shows("Manga")
        compose.onNodeWithText("Manga").performClick()
        shows("Manga")
        compose.onNodeWithText("Manga").assertIsSelected()
    }

    private fun signIn(): Session.SignedIn =
        waited {
            graph.signIn.start { error("Sandbox sign-in opened a browser") }
            val step = graph.signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed }
            assertEquals(SignInStep.SignedIn, step)
            graph.session.observeSession().first { it is Session.SignedIn } as Session.SignedIn
        }

    @Test
    fun `sandbox list retries offline and opens a manga title with Back to the cached list`() {
        val account = signIn()
        graph.scenarios.set(Scenario(offline = true))
        openList()
        shows("No connection")
        shows("Retry")
        compose.onNodeWithText("Retry").performClick()
        shows("No connection")

        graph.scenarios.set(Scenario())
        shows("Paper Observatory")
        val before = waited { manga.stored(account.signIn) }
        graph.scenarios.set(Scenario(offline = true))
        compose.onNodeWithText("Paper Observatory").performClick()
        compose.onNode(isHeading() and hasText("Paper Observatory")).assertExists()
        shows("No connection")
        compose.onNodeWithText("Manga\u00A0· Finished").assertDoesNotExist()
        shows("Retry")
        compose.onNodeWithText("Retry").performClick()
        shows("Retry")
        compose.onNode(isHeading() and hasText("Paper Observatory")).assertExists()
        graph.scenarios.set(Scenario())
        shows("Manga\u00A0· Finished")
        compose.activityRule.scenario.recreate()
        shows("Manga\u00A0· Finished")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        shows("Manga")
        compose.onNodeWithText("Manga").assertIsSelected()
        shows("Paper Observatory")

        val afterTitleRefresh = waited { manga.stored(account.signIn) }
        assertEquals((before as Outcome.Success).value?.entries, (afterTitleRefresh as Outcome.Success).value?.entries)
        graph.scenarios.set(Scenario(offline = true))
        val list =
            compose.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                    hasAnyDescendant(hasText("Paper Observatory")),
            )
        list.performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        shows("No connection")
        shows("Paper Observatory")
        assertEquals(afterTitleRefresh, waited { manga.stored(account.signIn) })
        compose.onNodeWithText("Paper Observatory").performClick()
        shows("Manga\u00A0· Finished")
        graph.scenarios.set(Scenario())
        assertEquals(emptyList<Any>(), connections.asked)
    }

    @Test
    fun `sandbox sign out closes the manga route and the next sign-in builds its own snapshot`() {
        val old = signIn()
        openList()
        shows("Paper Observatory")
        val before = (waited { manga.stored(old.signIn) } as Outcome.Success).value
        signOut(old)
        shows("Anime")
        compose.onNodeWithText("Anime").assertIsSelected()
        compose.onNodeWithText("Paper Observatory").assertDoesNotExist()

        val next = signIn()
        assertNotEquals(old.owner, next.owner)
        assertEquals(Outcome.Failure(DataError.ListEnded), waited { manga.stored(old.signIn) })
        assertEquals(Outcome.Success(null), waited { manga.stored(next.signIn) })
        time.ahead = Duration.ofMinutes(1)
        openList()
        shows("Paper Observatory")
        val after = (waited { manga.stored(next.signIn) } as Outcome.Success).value
        assertEquals(next.owner, after?.owner)
        assertEquals(before?.entries, after?.entries)
        assertEquals(emptyList<Any>(), connections.asked)
    }

    @Test
    fun `the expiry scenario expires a manga read but keeps its cached snapshot`() {
        val account = signIn()
        openList()
        shows("Paper Observatory")
        val before = waited { manga.stored(account.signIn) }

        graph.scenarios.set(Scenario(sessionExpired = true))
        val list =
            compose.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                    hasAnyDescendant(hasText("Paper Observatory")),
            )
        list.performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        shows("Session expired")
        assertEquals(Session.Expired(account.signIn), waited { graph.session.observeSession().first() })
        assertEquals(before, waited { manga.stored(account.signIn) })
        shows("Paper Observatory")
        assertEquals(emptyList<Any>(), connections.asked)
    }

    @Test
    fun `the personal row saves and the title can retry a refused edit`() {
        val account = signIn()
        openList()
        shows("Paper Observatory")
        val row = compose.onNode(hasText("Paper Observatory") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        compose.waitUntil(WAIT) { runCatching { row.fetchSemanticsNode() }.isSuccess }
        row.performSemanticsAction(SemanticsActions.OnLongClick)
        shows("Save")
        setChapters("23")
        compose.onNodeWithText("Save").performClick()
        waitForChapters(account, 23)
        awaitEditClosed()
        shows("23\u00A0chapters", substring = true)

        compose.onNodeWithText("Paper Observatory").performClick()
        shows("Reading")
        readingStatus.performClick()
        shows("Save")
        graph.scenarios.set(Scenario(writes = WriteFault.Refused))
        setChapters("24")
        compose.onNodeWithText("Save").performClick()
        shows("Could not save")
        shows("Retry")
        assertEquals(23, chapters(account))
        graph.scenarios.set(Scenario())
        compose.onNodeWithText("Retry").performClick()
        waitForChapters(account, 24)
        awaitEditClosed()
        shows("24\u00A0chapters", substring = true)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        shows("Manga")
        compose.onNodeWithText("Manga").assertIsSelected()
        shows("24\u00A0chapters", substring = true)
    }

    @Test
    fun `a saved status moves the row and Back stays on the destination tab`() {
        val first = signIn()
        openList()
        shows("Paper Observatory")
        compose
            .onNode(hasText("Paper Observatory") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        val editor = compose.onNodeWithContentDescription("Chapters read")
        editor.assertExists()
        compose.onNode(hasText("Dropped") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        compose.onNodeWithText("Save").performClick()
        awaitEditClosed()
        shows("No titles in Reading")
        compose.onNodeWithText("Reading").assertIsSelected()
        compose.onNode(hasText("Dropped") and isSelectable()).performClick()
        compose.onNodeWithText("Paper Observatory").assertIsDisplayed().performClick()
        shows("Manga\u00A0· Finished")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Dropped").assertIsSelected()
        compose.onNodeWithText("Paper Observatory").assertIsDisplayed()

        signOut(first)
        signIn()
        openList()
        shows("Paper Observatory")
        compose.onNodeWithText("Reading").assertIsSelected()
    }

    @Test
    fun `replacement closes a failed edit and keeps its retry from the next account`() {
        val first = signIn()
        openList()
        shows("Paper Observatory")
        compose.onNodeWithText("Paper Observatory").performClick()
        shows("Reading")
        readingStatus.performClick()
        shows("Save")
        graph.scenarios.set(Scenario(writes = WriteFault.Refused))
        setChapters("27")
        compose.onNodeWithText("Save").performClick()
        shows("Could not save")
        signOut(first)
        awaitEditClosed()
        compose.onNodeWithText("Could not save").assertDoesNotExist()
        graph.scenarios.set(Scenario())
        val replacement = signIn()
        openList()
        shows("Paper Observatory")
        assertNotEquals(27, chapters(replacement))
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `removal can be cancelled then reconciled and retried and the replacement starts intact`() {
        val first = signIn()
        openList()
        shows("Paper Observatory")
        compose.onNodeWithText("Paper Observatory").performClick()
        shows("Reading")
        readingStatus.performClick()
        shows("Remove from list")
        compose.onNodeWithText("Remove from list").performClick()
        shows("Remove Paper Observatory from your list?")
        compose.onNodeWithText("Cancel").performClick()
        val before = chapters(first)
        assertEquals(12, before)

        graph.scenarios.set(Scenario(writes = WriteFault.Refused))
        compose.onNodeWithText("Remove from list").performClick()
        compose.onNodeWithText("Remove").performClick()
        shows("Could not confirm removal")
        compose.onNodeWithText("Retry").assertDoesNotExist()
        assertEquals(before, chapters(first))
        graph.scenarios.set(Scenario())
        compose.onNodeWithText("Refresh list").performClick()
        shows("Retry")
        compose.onNodeWithText("Retry").performClick()
        awaitEditClosed()
        readingStatus.assertDoesNotExist()
        compose.onNode(isHeading() and hasText("Paper Observatory")).assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        shows("Manga")
        compose.onNodeWithText("Manga").assertIsSelected()
        compose.onNodeWithText("Paper Observatory").assertDoesNotExist()
        assertEquals(null, chapters(first))
        compose.activityRule.scenario.recreate()
        shows("Manga")
        compose.onNodeWithText("Paper Observatory").assertDoesNotExist()
        signOut(first)
        val replacement = signIn()
        openList()
        shows("Paper Observatory")
        assertEquals(before, chapters(replacement))
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `incoming links retire an unsubmitted draft and a removal confirmation`() {
        val account = signIn()
        openList()
        shows("Paper Observatory")
        val links = checkNotNull(RuntimeEnvironment.getApplication().appLinks)
        val paper = Link.MangaTitle(MangaId(1))
        val lantern = Link.MangaTitle(MangaId(2))
        for (confirming in listOf(false, true)) {
            links.opened(paper)
            shows("Paper Observatory")
            shows("Reading")
            readingStatus.performClick()
            shows("Save")
            setChapters("27")
            if (confirming) {
                compose.onNodeWithText("Remove from list").performClick()
                shows("Remove Paper Observatory from your list?")
            }
            links.opened(lantern)
            shows("The Lantern District")
            awaitEditClosed()
            compose.onNodeWithText("Remove Paper Observatory from your list?").assertDoesNotExist()
            assertEquals(12, chapters(account))
        }
    }

    @Test
    fun `a cached linked title gains its reading status button after cold membership fails offline and reconnects`() {
        val account = signIn()
        val id = MangaId(1)
        waited {
            val owner = graph.manga.observeOwner().first { it.id == account.signIn }
            check(graph.manga.refresh(id, owner) { true } is Outcome.Success)
            assertEquals(Outcome.Success(null), manga.stored(account.signIn))
        }
        graph.scenarios.set(Scenario(offline = true))
        checkNotNull(RuntimeEnvironment.getApplication().appLinks).opened(Link.MangaTitle(id))
        shows("Paper Observatory")
        shows("Could not check your list")
        readingStatus.assertDoesNotExist()
        graph.scenarios.set(Scenario())
        shows("Reading")
        compose.onNodeWithText("Could not check your list").assertDoesNotExist()
    }

    @Test
    fun `removal Retry names its target after leaving the sheet`() {
        val account = signIn()
        openList()
        shows("Paper Observatory")
        compose.onNodeWithText("Paper Observatory").performClick()
        shows("Reading")
        readingStatus.performClick()
        shows("Remove from list")
        graph.scenarios.set(Scenario(writes = WriteFault.Refused))
        compose.onNodeWithText("Remove from list").performClick()
        compose.onNodeWithText("Remove").performClick()
        shows("Could not confirm removal")
        graph.scenarios.set(Scenario())
        compose.onNodeWithText("Refresh list").performClick()
        shows("Could not remove")
        checkNotNull(RuntimeEnvironment.getApplication().appLinks).opened(Link.MangaTitle(MangaId(2)))
        shows("The Lantern District")
        awaitEditClosed()
        shows("Could not remove Paper Observatory")
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(WAIT) {
            shadowOf(Looper.getMainLooper()).idle()
            chapters(account) == null
        }
        shows("The Lantern District")
    }

    private fun signOut(old: Session.SignedIn) {
        runBlocking {
            val current = graph
            val signingOut = async(Dispatchers.Default) { current.signOut(old) }
            compose.waitUntil(WAIT) {
                shadowOf(Looper.getMainLooper()).idle()
                signingOut.isCompleted
            }
            signingOut.await()
        }
    }

    private fun awaitEditClosed() {
        compose.waitUntil(WAIT) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithContentDescription("Chapters read").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun setChapters(value: String) {
        val input = compose.onNodeWithContentDescription("Chapters read")
        input.performTextReplacement(value)
        input.performImeAction()
    }

    private fun chapters(account: Session.SignedIn): Int? =
        (waited { manga.stored(account.signIn) } as Outcome.Success)
            .value
            ?.entries
            ?.firstOrNull { it.titles.romaji == "Paper Observatory" }
            ?.chaptersRead

    private fun waitForChapters(
        account: Session.SignedIn,
        expected: Int,
    ) {
        compose.waitUntil(WAIT) {
            shadowOf(Looper.getMainLooper()).idle()
            chapters(account) == expected
        }
    }

    private companion object {
        const val WAIT = 20_000L
    }
}
