package io.github.kauri_off.kiroku.wiring

import android.os.Looper
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.data.network.AnotherTitleAnswer
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.reports.ReportLog
import io.github.kauri_off.kiroku.rules.KotlinSource
import io.github.kauri_off.kiroku.rules.projectDir
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.SourceChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.time.Clock

// What a debug or preview build does with a report, as the graph is given it.
@RunWith(RobolectricTestRunner::class)
class DebugReporterTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val mainThread get() = shadowOf(Looper.getMainLooper())

    // S8, through the graph of a debug build: here no Keystore answers, so stored tokens do not open.
    @Test
    fun `tokens that do not open at a start are logged, and the build does not crash`() =
        runTest {
            val signIn = "0f8fad5b-d9cb-469f-a165-70867728950e"
            // Sealed tokens, as the session writes them: a one, the sign-in's id, and what was sealed.
            File(context.noBackupFilesDir, "account.tokens").writeBytes(byteArrayOf(1) + signIn.encodeToByteArray() + ByteArray(48))
            assertTrue(SourceChoice(context).choose(DataSource.Account))
            val graph = createGraph(context, "0123abcd", Clock.systemUTC(), backgroundScope)

            assertEquals(Session.Expired(SignInId(signIn)), graph.session.observeSession().first())

            mainThread.idle()
            val logged = ShadowLog.getLogsForTag(LOG_TAG).map { it.msg }
            assertEquals("Unexpected: io.github.kauri_off.kiroku.data.session.TokensNotRead", logged.first())
            assertTrue(logged.toString(), logged[1].startsWith("Stands for: java.security."))
        }

    // A directory in the file's place: a token file that is there and cannot be read at all.
    @Test
    fun `a token file that cannot be read at a start is logged, and the build does not crash`() =
        runTest {
            File(context.noBackupFilesDir, "account.tokens").mkdirs()
            assertTrue(SourceChoice(context).choose(DataSource.Account))
            val graph = createGraph(context, "0123abcd", Clock.systemUTC(), backgroundScope)
            backgroundScope.launch { graph.session.observeSession().collect { error("The session has a value: $it") } }

            val logged = withContext(Dispatchers.Default) { withTimeout(10_000) { logged { it.size >= 2 } } }
            mainThread.idle()
            assertEquals("Unexpected: io.github.kauri_off.kiroku.data.session.TokensNotRead", logged.first())
            assertTrue(logged.toString(), logged[1].startsWith("Stands for: java.io."))
        }

    @Test
    fun `a file of choices that cannot be read at a start is logged, and the build does not crash`() =
        runTest {
            File(context.noBackupFilesDir, "account.preferences_pb").writeText("not a preferences file")
            assertTrue(SourceChoice(context).choose(DataSource.Account))
            val graph = createGraph(context, "0123abcd", Clock.systemUTC(), backgroundScope)

            val sorts = withContext(Dispatchers.Default) { withTimeout(10_000) { graph.preferences.observeListSorts().first() } }
            assertEquals(ListStatus.entries.associateWith(TabSort::defaultFor), sorts)

            mainThread.idle()
            val logged = ShadowLog.getLogsForTag(LOG_TAG).map { it.msg }
            assertEquals("Unexpected: io.github.kauri_off.kiroku.data.preferences.PreferencesFailure", logged.first())
        }

    private suspend fun logged(enough: (List<String>) -> Boolean): List<String> {
        while (true) {
            val logged = ShadowLog.getLogsForTag(LOG_TAG).map { it.msg }
            if (enough(logged)) return logged
            delay(10)
        }
    }

    // Nothing fails on a value of MAL's the app cannot read, and the report of it is not silent either.
    @Test
    fun `an answer of MAL's read in part or for another title is logged, and the build does not crash`() {
        reportUnexpected(RepairedAnswer(setOf("start_date")))
        reportUnexpected(AnotherTitleAnswer())
        mainThread.idle()

        val logged = ShadowLog.getLogsForTag(LOG_TAG).map { it.msg }.filter { it.startsWith("Unexpected: ") }
        assertEquals(listOf(RepairedAnswer::class.java.name, AnotherTitleAnswer::class.java.name).map { "Unexpected: $it" }, logged)
    }

    @Test
    fun `any other report crashes the debuggable build on the main thread, kept for the debug menu first`() {
        val log = ReportLog()
        val nobodyExpected = IllegalArgumentException("unexpected")
        reporter(loud = true, log)(nobodyExpected)

        assertEquals(listOf("Unexpected: ${IllegalArgumentException::class.java.name}"), log.reports.value.map { it.lines.first() })
        val crash = assertThrows(IllegalStateException::class.java) { mainThread.idle() }
        assertEquals(nobodyExpected, crash.cause)
        assertEquals(emptyList<String>(), ShadowLog.getLogsForTag(LOG_TAG).map { it.msg })
    }

    // The preview build: the owner's daily app, which a report never stops (section 2.3), whatever its type.
    @Test
    fun `a build that is not debuggable logs any report as a release does, keeps it for the debug menu and goes on`() {
        val log = ReportLog()
        val nobodyExpected =
            listOf(
                IllegalArgumentException("unexpected", IllegalStateException("Sousou no Frieren")),
                SecurityException("The system's network calls refused"),
            )
        nobodyExpected.forEach(reporter(loud = false, log))
        mainThread.idle()

        assertEquals(nobodyExpected.flatMap(::typesOf), ShadowLog.getLogsForTag(LOG_TAG).map { it.msg })
        val kept =
            log.reports.value
                .reversed()
                .map { report -> report.lines.filterNot { it.startsWith(FRAME) } }
        assertEquals(nobodyExpected.map { e -> typesOf(e).filterNot { it.startsWith(FRAME) } }, kept)
    }

    // The debug build's sources are compiled into the preview build too, where BuildConfig.DEBUG is false.
    @Test
    fun `the build's reporter is loud exactly when the build is debuggable`() {
        val source = File(projectDir, "app/src/debug/kotlin/io/github/kauri_off/kiroku/wiring/GraphFactory.kt")
        val line = "internal val reportUnexpected: (Throwable) -> Unit = reporter(loud = BuildConfig.DEBUG, reportLog)"
        assertTrue(line in KotlinSource(source.readText()).code.lines())
        assertTrue(BuildConfig.DEBUG)
    }
}
