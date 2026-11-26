package io.github.kauri_off.kiroku.recorder

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.data.manga.MangaMutationMethod
import io.github.kauri_off.kiroku.data.recorder.Halt
import io.github.kauri_off.kiroku.data.recorder.ListAfter
import io.github.kauri_off.kiroku.data.recorder.MangaCaptureState
import io.github.kauri_off.kiroku.data.recorder.MangaCaptureTarget
import io.github.kauri_off.kiroku.data.recorder.ReadCapture
import io.github.kauri_off.kiroku.data.recorder.RecordedTitle
import io.github.kauri_off.kiroku.data.recorder.Recording
import io.github.kauri_off.kiroku.data.recorder.RecordingRequests
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.SourceChoice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

// The recorder writes to the owner's list: it starts only from this section, after the dialog that names the title.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class RecorderSectionTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val title = RecordedTitle(1, "Cowboy Bebop", 26)
    private val events = mutableListOf<RecorderEvent>()
    private val folder = File("/storage/emulated/0/Android/data/kiroku/files/account.recorded-run")

    private fun show(
        recording: Recording?,
        typed: String = "1",
    ) = compose.setContent { Column { RecorderSection(RecorderState(recording, typed)) { events += it } } }

    private fun startsOn(source: DataSource) = check(SourceChoice(RuntimeEnvironment.getApplication()).choose(source))

    @Test
    fun `manga capture selects the editor action`() {
        compose.setContent {
            Column {
                RecorderSection(RecorderState(Recording.Idle, manga = MangaCaptureState.Ready, mangaId = "17")) { events += it }
            }
        }
        compose.onNodeWithText("Arm capture for Save").performClick()
        compose.onNodeWithText("Arm capture for Remove").performClick()
        assertEquals(
            listOf(RecorderEvent.ArmManga(MangaMutationMethod.Patch), RecorderEvent.ArmManga(MangaMutationMethod.Delete)),
            events,
        )
    }

    @Test
    fun `signed out manga capture cannot be armed`() {
        compose.setContent {
            Column {
                RecorderSection(RecorderState(Recording.Idle, manga = MangaCaptureState.Ready, mangaId = "17", mangaSignedIn = false)) {
                    events += it
                }
            }
        }
        compose.onNodeWithText("Arm capture for Save").assertIsNotEnabled()
        compose.onNodeWithText("Arm capture for Remove").assertIsNotEnabled()
        assertEquals(emptyList<RecorderEvent>(), events)
    }

    @Test
    fun `armed manga capture shows the selected action and can be disarmed`() {
        val target = MangaCaptureTarget(MangaId(17), MangaMutationMethod.Delete)
        compose.setContent {
            Column { RecorderSection(RecorderState(Recording.Idle, manga = MangaCaptureState.Armed(target))) { events += it } }
        }
        compose.onNodeWithText("DELETE capture armed for manga ID\u00A017", substring = true).assertExists()
        compose.onNodeWithText("Arm capture for Save").assertDoesNotExist()
        compose.onNodeWithText("Record my manga list").assertIsNotEnabled()
        compose.onNodeWithText("Disarm capture").performClick()
        assertEquals(listOf(RecorderEvent.DisarmManga(target)), events)
    }

    @Test
    fun `nothing is recorded before the dialog that names the title and what will be written is confirmed`() {
        show(Recording.Checked(title))

        compose.onNodeWithText("Record on this title?").assertExists()
        compose.onNodeWithText("“Cowboy Bebop”, 26\u00A0episodes").assertExists()
        compose.onNodeWithText("Kiroku will add this title to your list on MyAnimeList", substring = true).assertExists()
        val requests = "It sends\u00A0$RecordingRequests\u00A0requests, two seconds apart, which takes about two minutes"
        compose.onNodeWithText(requests).assertExists()
        assertEquals(47, RecordingRequests)
        assertEquals(emptyList<RecorderEvent>(), events)

        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf<RecorderEvent>(RecorderEvent.Dismiss), events)
        compose.onNodeWithText("Record").performClick()
        assertEquals(listOf(RecorderEvent.Dismiss, RecorderEvent.Record(title)), events)
    }

    @Test
    fun `the button under the title's id only asks MAL about the title`() {
        show(Recording.Idle)

        compose.onNodeWithText("Record on this title?").assertDoesNotExist()
        compose.onNodeWithText("Check title").performClick()

        assertEquals(listOf<RecorderEvent>(RecorderEvent.Check), events)
    }

    @Test
    fun `without an id there is nothing to check`() {
        show(Recording.Idle, typed = "")

        compose.onNodeWithText("Check title").assertIsNotEnabled()
    }

    @Test
    fun `read actions start the selected capture without a title id or a write confirmation`() {
        show(Recording.Idle, typed = "")

        compose.onNodeWithText("Record anime suggestions").performClick()
        compose.onNodeWithText("Record my manga list").performClick()
        compose.onNodeWithText("Record on this title?").assertDoesNotExist()
        assertEquals(listOf(RecorderEvent.RecordReads(ReadCapture.Suggestions), RecorderEvent.RecordReads(ReadCapture.MangaList)), events)
    }

    @Test
    fun `a read in progress blocks every recorder action`() {
        show(Recording.Reading)

        compose.onNodeWithText("Reading from MyAnimeList").assertExists()
        compose.onNodeWithText("Record anime suggestions").assertIsNotEnabled()
        compose.onNodeWithText("Record my manga list").assertIsNotEnabled()
        compose.onNodeWithText("Check title").assertIsNotEnabled()
    }

    @Test
    fun `a private run with no artifact reports the storage failure without a path`() {
        show(Recording.ReadStopped("files", Halt.Storage, 0, null))

        compose.onNodeWithText("The device could not store a file").assertExists()
        compose.onNodeWithText("Folder:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Done").assertIsEnabled()
    }

    @Test
    fun `while the title may still be on the list the section says where to look, removes it at a touch and starts nothing new`() {
        show(Recording.Stopped(title, "score_set", Halt.Refused(429), 31, folder, ListAfter.Left))

        compose.onNodeWithText("The recording stopped at the request score_set").assertExists()
        compose.onNodeWithText("MyAnimeList is refusing requests (answer\u00A0429), try again later").assertExists()
        compose.onNodeWithText("Answers so far:\u00A031. They replaced no earlier recording").assertExists()
        compose.onNodeWithText("Folder: ${folder.path}").assertExists()
        compose.onNodeWithText("myanimelist.net/anime/1", substring = true).assertExists()
        compose.onNodeWithText("“Cowboy Bebop” may still be on your list", substring = true).assertExists()
        compose.onNodeWithText("Check title").assertIsNotEnabled()
        compose.onNodeWithText("Record anime suggestions").assertIsNotEnabled()
        compose.onNodeWithText("Record my manga list").assertIsNotEnabled()
        compose.onNodeWithText("Done").assertDoesNotExist()

        compose.onNodeWithText("Remove now").performClick()
        assertEquals(listOf<RecorderEvent>(RecorderEvent.Remove), events)
    }

    @Test
    fun `a recording that ended says where its answers are and what became of the list`() {
        show(Recording.Recorded(title, 47, folder, ListAfter.Same))

        compose.onNodeWithText("Recording finished. Answers stored:\u00A047").assertExists()
        compose
            .onNodeWithText(
                "“Cowboy Bebop” was added to your list and removed again. The list holds the same titles as before",
            ).assertExists()
        compose.onNodeWithText("To check: “Add to My List” should be offered at myanimelist.net/anime/1").assertExists()
        compose.onNodeWithText("Remove now").assertDoesNotExist()
        compose.onNodeWithText("Check title").assertIsEnabled()

        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf<RecorderEvent>(RecorderEvent.Dismiss), events)
    }

    @Test
    fun `a title MAL still shows after its removal is said to be there, and can be removed at a touch`() {
        show(Recording.Leftover(title, ListAfter.Left, Halt.StillOnTheList))

        compose.onNodeWithText("MyAnimeList answered the removal and still shows the title on your list").assertExists()
        compose.onNodeWithText("“Cowboy Bebop” may still be on your list", substring = true).assertExists()
        compose.onNodeWithText("left on your list, is removed", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Remove now").performClick()
        assertEquals(listOf<RecorderEvent>(RecorderEvent.Remove), events)
    }

    @Test
    fun `a recording whose title the list still holds offers the removal, not the end`() {
        show(Recording.Recorded(title, 46, folder, ListAfter.Left))

        compose.onNodeWithText("“Cowboy Bebop” may still be on your list", substring = true).assertExists()
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithText("Check title").assertIsNotEnabled()
        compose.onNodeWithText("Remove now").assertExists()
    }

    @Test
    fun `a title that is on the list is refused in words`() {
        show(Recording.NotStarted(Halt.OnTheList))

        compose.onNodeWithText("This title is on your list. Choose one that is not").assertExists()
        compose.onNodeWithText("Record on this title?").assertDoesNotExist()
    }

    @Test
    fun `on the demo there is no recorder, and the section says so`() {
        startsOn(DataSource.Demo)
        compose.setContent { Column { RecorderRoute() } }

        compose.onNodeWithText("The recorder works only on MyAnimeList. Choose MyAnimeList under Data source").assertExists()
        compose.onNodeWithText("Check title").assertDoesNotExist()
        compose.onNodeWithText("Records what MyAnimeList answers", substring = true).assertDoesNotExist()
    }

    // Signed out, the session answers before any request is made, so this test reaches no host.
    @Test
    fun `on the real account the section asks the graph's recorder, which asks the session first`() {
        startsOn(DataSource.Account)
        compose.setContent { Column { RecorderRoute() } }

        compose.onNodeWithText("Title id on MyAnimeList").performTextInput("5x1")
        compose.onNodeWithText("51").assertExists()
        compose.onNodeWithText("Check title").performClick()

        compose.waitUntil(WAIT) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText(NOT_SIGNED_IN),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Record on this title?").assertDoesNotExist()
    }

    private companion object {
        const val WAIT = 15_000L
        const val NOT_SIGNED_IN = "Nobody is signed in. Sign in and try again"
    }
}
