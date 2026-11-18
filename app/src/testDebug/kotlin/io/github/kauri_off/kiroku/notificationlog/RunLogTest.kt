package io.github.kauri_off.kiroku.notificationlog

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.notify.Recheck
import io.github.kauri_off.kiroku.core.notify.RunEnd
import io.github.kauri_off.kiroku.core.notify.RunReport
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.notify.SentNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor

// The debug menu's log of the notification engine's runs: every run in it as the engine reported it, across processes.
class RunLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file get() = File(folder.root, "sandbox.runs")
    private val lines = mutableListOf<String>()

    private fun log(
        limit: Int = RunLog.LIMIT,
        files: Executor = Executor(Runnable::run),
    ) = RunLog(file, limit, files, lines::add)

    private fun sent(
        anime: Anime,
        episode: Int,
        late: Duration,
        shown: Boolean = true,
    ) = SentNotification(anime, episode, AT.minusSeconds(1800), AT, AT.plus(late), Recheck.Answered, shown)

    private fun report(
        trigger: RunTrigger,
        at: Instant = AT,
        posted: List<SentNotification> = emptyList(),
    ) = RunReport(trigger, at, at.plusMillis(240), RunEnd.Done, posted = posted, alarm = at.plusSeconds(3600))

    @Test
    fun `a run is kept as the engine reported it, newest first, and a new process reads every run back from the file`() {
        // A name with the words the file is made of, and one with a line break, which the file keeps on one line.
        val odd = FixtureAnime.exactToWatch.copy(titles = Titles("run post withdrawn - true 12"))
        val broken = FixtureAnime.longTitle.copy(titles = Titles("Two\nlines"))
        val reports =
            listOf(
                report(RunTrigger.AppOpened, AT.minusSeconds(60)),
                RunReport(
                    RunTrigger.Alarm,
                    AT,
                    AT.plusSeconds(5),
                    RunEnd.Failed,
                    Recheck.Late,
                    listOf(sent(odd, 3, Duration.ofSeconds(3)), sent(broken, 12, Duration.ofMinutes(61), shown = false)),
                    setOf(FixtureAnime.exactOnBreak.id, FixtureAnime.exactToWatch.id),
                    alarm = null,
                    alarmFailed = true,
                ),
                report(RunTrigger.Changed, AT.plusSeconds(60)),
            )
        val first = log()
        reports.forEach(first::keep)
        val expected = reports.map(LoggedRun::of).asReversed()
        assertEquals(expected, first.runs.value)
        assertEquals(Duration.ofSeconds(3), expected[1].posted[0].delay)
        assertEquals("run post withdrawn - true 12", expected[1].posted[0].title)

        val next = log()

        val read = expected.map { run -> run.copy(posted = run.posted.map { it.copy(title = it.title.replace('\n', ' ')) }) }
        assertEquals(read, next.runs.value)
    }

    @Test
    fun `runs of the new process come before those of the file, which is read before anything is written to it`() {
        log().apply { keep(report(RunTrigger.Alarm, AT)) }
        val queued = mutableListOf<Runnable>()
        val next = log(files = Executor(queued::add))
        next.keep(report(RunTrigger.AppOpened, AT.plusSeconds(60)))
        assertEquals(listOf(RunTrigger.AppOpened), next.runs.value.map { it.trigger })

        queued.forEach(Runnable::run)

        assertEquals(listOf(RunTrigger.AppOpened, RunTrigger.Alarm), next.runs.value.map { it.trigger })
        assertEquals(listOf(RunTrigger.AppOpened, RunTrigger.Alarm), log().runs.value.map { it.trigger })
    }

    @Test
    fun `the file is bounded at twice the limit, and the log holds the latest runs`() {
        val log = log(limit = 3)
        repeat(20) { log.keep(report(RunTrigger.entries[it % RunTrigger.entries.size], AT.plusSeconds(it * 60L))) }

        assertEquals(3, log.runs.value.size)
        val inFile = file.readLines().count { it.startsWith("run ") }
        assertTrue("$inFile", inFile in 3..6)
        assertEquals(log.runs.value, log(limit = 3).runs.value)
        assertEquals((17 until 20).map { AT.plusSeconds(it * 60L) }.reversed(), log.runs.value.map { it.began })
    }

    @Test
    fun `the file stays bounded when each of many processes writes one run`() {
        repeat(40) { log(limit = 3).keep(report(RunTrigger.entries[it % RunTrigger.entries.size], AT.plusSeconds(it * 60L))) }

        val inFile = file.readLines().count { it.startsWith("run ") }
        assertTrue("$inFile", inFile in 3..6)
        assertEquals((37 until 40).map { AT.plusSeconds(it * 60L) }.reversed(), log(limit = 3).runs.value.map { it.began })
    }

    @Test
    fun `a file over the bound is cut back when it is read, also with runs that cannot be read`() {
        log(limit = 100).apply { repeat(10) { keep(report(RunTrigger.Alarm, AT.plusSeconds(it * 60L))) } }
        file.appendText("run Sundial\n".repeat(10))

        val runs = log(limit = 3).runs.value

        assertEquals((7 until 10).map { AT.plusSeconds(it * 60L) }.reversed(), runs.map { it.began })
        assertEquals(3, file.readLines().count { it.startsWith("run ") })
    }

    @Test
    fun `forgotten, nothing it kept is shown or in the file, also what the file held before it was read`() {
        log().keep(report(RunTrigger.Alarm, AT, listOf(sent(FixtureAnime.exactToWatch, 4, Duration.ofSeconds(3)))))
        val queued = mutableListOf<Runnable>()
        val next = log(files = Executor(queued::add))

        next.forget()
        next.keep(
            RunReport(
                RunTrigger.Changed,
                AT.plusSeconds(60),
                AT.plusSeconds(60),
                RunEnd.SignedOut,
                withdrawn = setOf(FixtureAnime.exactToWatch.id),
            ),
        )
        queued.forEach(Runnable::run)

        val kept =
            listOf(
                LoggedRun(
                    RunTrigger.Changed,
                    AT.plusSeconds(60),
                    AT.plusSeconds(60),
                    RunEnd.SignedOut,
                    Recheck.NotAsked,
                    emptyList(),
                    emptyList(),
                    null,
                    false,
                    forgotten = 1,
                ),
            )
        assertEquals(kept, next.runs.value)
        assertEquals(kept, log().runs.value)
        assertFalse(file.readText(), "${FixtureAnime.exactToWatch.id.value}" in file.readText())
        assertFalse(file.readText(), FixtureAnime.exactToWatch.titles.romaji in file.readText())
    }

    @Test
    fun `a run whose engine could not be built is kept and read back as such`() {
        log().notBuilt(RunTrigger.AppOpened, AT)

        assertEquals(listOf(LoggedRun.notBuilt(RunTrigger.AppOpened, AT)), log().runs.value)
        assertFalse(
            log()
                .runs.value
                .single()
                .built,
        )
        assertTrue(lines.single(), "not built" in lines.single())
    }

    @Test
    fun `a run the file holds that cannot be read is left out, and the rest are read`() {
        log().apply {
            keep(report(RunTrigger.Alarm, AT))
            keep(report(RunTrigger.AppOpened, AT.plusSeconds(60)))
        }
        file.writeText(file.readText().replace("run Alarm", "run Sundial") + "run AppOpened yesterday\npost 1 2\n")

        assertEquals(listOf(RunTrigger.AppOpened), log().runs.value.map { it.trigger })
    }

    @Test
    fun `each run is one line of the device's log, with its trigger and each post's delay, and no title's name`() {
        log().keep(report(RunTrigger.Alarm, posted = listOf(sent(FixtureAnime.exactToWatch, 4, Duration.ofMillis(1500), shown = false))))

        val line = lines.single()
        assertTrue(line, "Alarm" in line)
        assertTrue(line, "${FixtureAnime.exactToWatch.id.value}:4 +1500 ms not shown" in line)
        assertFalse(line, FixtureAnime.exactToWatch.titles.romaji in line)
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-11-18T09:30:00Z")
    }
}
