package io.github.kauri_off.kiroku.wiring

import android.util.Log
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.rules.KotlinSource
import io.github.kauri_off.kiroku.rules.appSources
import io.github.kauri_off.kiroku.rules.projectDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException

// A report names types and frames, and never a message, which could quote the list.
@RunWith(RobolectricTestRunner::class)
class ReportsTest {
    private val title = "Sousou no Frieren"

    @Test
    fun `the log of an exception whose message holds a title holds its type and not the title`() {
        logTypes(IllegalStateException("Could not store $title", IllegalArgumentException(title)))

        val logged = ShadowLog.getLogsForTag(LOG_TAG)
        assertTrue(logged.isNotEmpty() && logged.all { it.type == Log.ERROR && it.throwable == null })
        val text = logged.joinToString("\n") { it.msg }
        assertFalse(text, "Frieren" in text)
        assertEquals("Unexpected: java.lang.IllegalStateException", logged.first().msg)
        assertTrue(text, "Caused by: java.lang.IllegalArgumentException" in text)
    }

    // A release build's sources are in no unit test, so its lines are read here, comments apart.
    @Test
    fun `a release build reports with that log, has no other source than the real account and logs no request`() {
        val release = File(projectDir, "app/src/release/kotlin/io/github/kauri_off/kiroku/wiring/GraphFactory.kt")
        val lines = KotlinSource(release.readText()).code.lines()
        for (line in listOf(
            "internal val reportUnexpected: (Throwable) -> Unit = ::logTypes",
            "internal val requestLog: ((String) -> Unit)? = null",
            "internal val otherSource: OtherSource? = null",
        )) {
            assertTrue(line, line in lines)
        }
    }

    // So no build hands a live graph another reporter or request log than its own factory's.
    @Test
    fun `the parts of a live graph are made in one place, with the build's reporter and request log`() {
        val making = Regex("""\bLiveParts\s*\(""")
        val made =
            appSources()
                .flatMap { KotlinSource(it.readText()).code.lines() }
                .filter { making.containsMatchIn(it) && "class LiveParts" !in it }
                .map { it.trim() }
        val only =
            "live: () -> AppGraph = " +
                "{ LiveGraph(LiveParts(context, LiveSource.Account, clientId, clock, scope, reportUnexpected, requestLog)) },"
        assertEquals(listOf(only), made)
    }

    @Test
    fun `what is said of an exception holds its types and frames and no message, its causes' included`() {
        val cause = IllegalArgumentException("No entry for $title")
        val thrown = IllegalStateException("Could not store $title, watched 12", IOException(title, cause))
        val lines = typesOf(thrown)

        assertFalse(lines.joinToString("\n"), lines.any { "Frieren" in it || "watched" in it || "entry" in it })
        assertEquals("Unexpected: java.lang.IllegalStateException", lines.first())
        assertEquals(
            listOf("Caused by: java.io.IOException", "Caused by: java.lang.IllegalArgumentException"),
            lines.filter { it.startsWith("Caused by: ") },
        )
        val frame = "\tat ${thrown.stackTrace.first()}"
        assertTrue(frame, frame in lines && ReportsTest::class.java.name in frame)
        assertEquals(3 + thrown.stackTrace.size + cause.stackTrace.size * 2, lines.size)
    }

    // The network layer and the session report an exception as a wrapper whose message is the exception's type.
    @Test
    fun `a message that is the name of a type is said, and one that only looks like a name is not`() {
        val standsFor = DataException::class.java.name
        assertEquals(
            listOf("Unexpected: java.lang.Exception", "Stands for: $standsFor"),
            typesOf(Exception(standsFor)).take(2),
        )
        for (message in listOf("Dr.Stone", "java.lang.NoSuchTitle", "Re.Zero", DataError.ServerError.toString(), "")) {
            assertEquals(message, 0, typesOf(Exception(message)).count { it.startsWith("Stands for") })
        }
    }

    @Test
    fun `an exception that is its own cause, or a long chain of causes, ends`() {
        val chain = generateSequence(Exception("0")) { Exception(title, it) }.take(40).last()
        assertTrue(typesOf(chain).count { it.startsWith("Caused by: ") } < 10)
    }
}
