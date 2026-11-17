package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.notify.NotifyCases.day
import io.github.kauri_off.kiroku.core.notify.NotifyCases.entry
import io.github.kauri_off.kiroku.core.notify.NotifyCases.hour
import io.github.kauri_off.kiroku.core.notify.NotifyCases.title
import io.github.kauri_off.kiroku.core.notify.NotifyCases.week
import io.github.kauri_off.kiroku.core.notify.NotifyCases.weekly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

// A1: whether a count is exact and when an episode airs is the airing calculator's answer, and no rule of notifications derives it.
class NotifyRulesTest {
    private val entries = listOf(entry())
    private val schedules = mapOf(title.id to weekly())
    private val record = NotificationRecord().following(entries, NotificationSettings(), week(0).minus(day))

    private fun at(
        now: Instant,
        calculate: (Anime, AiringSchedule?, Instant) -> Airing,
    ) = notificationsAt(entries, schedules, NotificationSettings(), record, now, calculate)

    @Test
    fun `the rule announces what the calculator answers, whatever the stored schedule holds`() {
        assertEquals(listOf(title.id to 3), at(week(2), ::airingAt).announced)
        assertEquals(notificationsAt(entries, schedules, NotificationSettings(), record, week(2)), at(week(2), ::airingAt))

        // A calculator that tells no air time: three episodes of the schedule have aired, and nothing is announced or planned.
        assertTrue(at(week(2)) { anime, schedule, now -> airingAt(anime, schedule, now).copy(airTimes = emptyList()) }.silent)
        // One that does not read the schedule, and so gives an estimate.
        assertTrue(at(week(2)) { anime, _, now -> airingAt(anime, null, now) }.silent)

        // One whose episodes air an hour after the schedule's: its times are the ones announced.
        val later = { anime: Anime, schedule: AiringSchedule?, now: Instant ->
            airingAt(anime, schedule?.let { it.copy(episodes = it.episodes.map { e -> e.copy(airsAt = e.airsAt.plus(hour)) }) }, now)
        }
        val before = at(week(2), later)
        assertEquals(emptyList<Due>(), before.due)
        assertEquals(listOf(Coming(title, 3, week(2).plus(hour), week(2).plus(hour))), before.coming)
        val after = at(week(2).plus(hour), later)
        assertEquals(listOf(Due(Announcement(title, 3, toWatch = 3, marks = 1), week(2).plus(hour), week(2).plus(hour))), after.due)

        // One whose count is ahead of the episodes it gives a time: the count to watch is its count.
        val ahead = {
            anime: Anime,
            schedule: AiringSchedule?,
            now: Instant,
            ->
            airingAt(anime.copy(airingStatus = AiringStatus.Finished), schedule, now)
        }
        assertEquals(listOf(Announcement(title, 12, toWatch = 12, marks = 1)), at(week(11), ahead).due.map { it.announcement })
        assertTrue(at(week(11).minusNanos(1), ahead).due.isEmpty())
    }

    @Test
    fun `the rules of notifications derive no aired count and no air time, and read no clock and no zone`() {
        val sources = notifySources()
        assertEquals(setOf("Notifiable.kt", "NotificationRecord.kt", "NotificationSettings.kt"), sources.map { it.name }.toSet())
        assertEquals(emptyList<String>(), sources.flatMap { notifyProblems(it.name, it.readText()) })
        // The calculator is named once, as the one the rule asks.
        val named = sources.flatMap { code(it.readText()).lines() }.filter { "airingAt" in it && !it.startsWith("import ") }
        assertEquals(listOf("::airingAt"), named.map { Regex("""\S*airingAt""").find(it)?.value })
    }

    @Test
    fun `a planted derivation fails`() {
        val planted =
            listOf(
                "val aired = schedules[id]?.episodes.orEmpty().filter { !it.airsAt.isAfter(now) }",
                "val count = schedule.episodes.count { it.airsAt <= now }",
                "val total = schedule.totalEpisodes",
                "if (schedule.doubtedOver != null) return",
                "val next = entry.anime.broadcast?.nextStart(now)",
                "val started = entry.anime.startDate",
                "if (anime.malEpisodes == watched) return",
                "if (entry.anime.airingStatus.effective == AiringStatus.Finished) return",
                "val count = AiredCount.Exact(schedule.size)",
                "val episode = ScheduledEpisode(number, at)",
                "val airing = Airing(count, null, null, null)",
                "val latest = airing.latestAiredAt",
                "val next = airing.nextEpisode",
                "val now = Instant.now()",
                "val now = clock.instant()",
                "fun notificationsAt(clock: Clock)",
                "val today = now.atZone(ZoneId.systemDefault()).toLocalDate()",
                "val day = LocalDate.now()",
                "val millis = System.currentTimeMillis()",
                "val zone = TimeZone.getDefault()",
            )
        for (source in planted) assertEquals(source, 1, notifyProblems("A.kt", source).size)
        val clean =
            "// The schedule's episodes are read by the calculator.\n" +
                "val airing = calculate(anime, schedules[anime.id], now)\n" +
                "val open = airing.airTimes.filter { it.number > watched }.map { it.airsAt.plus(delay) }\n"
        assertEquals(emptyList<String>(), notifyProblems("A.kt", clean))
    }
}

// The module's directory, wherever Gradle runs the tests from.
private val coreDir: File =
    generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }.resolve("core")

internal fun notifySources(): List<File> =
    File(coreDir, "src/main/kotlin/io/github/kauri_off/kiroku/core/notify")
        .listFiles()
        .orEmpty()
        .filter { it.extension == "kt" }
        .sorted()

// The source without its line comments.
private fun code(source: String): String = source.lines().joinToString("\n") { it.substringBefore("//") }

private val forbidden =
    mapOf(
        "reads what a schedule holds" to Regex("""\.(episodes|totalEpisodes|doubtedOver)\b"""),
        "reads MAL's airing facts" to Regex("""\.(broadcast|startDate|malEpisodes|airingStatus)\b"""),
        "builds a value of the calculator" to Regex("""\b(AiredCount\.(Exact|Estimated)|ScheduledEpisode|Airing)\("""),
        "reads the calculator's result for a screen" to Regex("""\.(latestAiredAt|nextEpisode|changesAt|premiere)\b"""),
        "reads a clock or a zone" to
            Regex(
                """\b(Clock|ZoneId|ZoneOffset|ZonedDateTime|OffsetDateTime|LocalDate|LocalDateTime|LocalTime|TimeZone)\b|\.now\(\)|\.instant\(\)|currentTimeMillis""",
            ),
    )

// A line of the notification rules that derives what only the airing calculator may.
internal fun notifyProblems(
    name: String,
    source: String,
): List<String> =
    code(source).lines().flatMapIndexed { index, line ->
        forbidden.filterValues { it.containsMatchIn(line) }.keys.map { "$name:${index + 1} $it: ${line.trim()}" }
    }
