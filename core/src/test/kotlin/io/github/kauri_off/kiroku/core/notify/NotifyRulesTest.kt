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
        assertEquals(rules + carriers, sources("notify").map { it.name }.toSet())
        val sources = sources("notify").filter { it.name in rules }
        assertEquals(emptyList<String>(), sources.flatMap { notifyProblems(it.name, it.readText()) })
        // The schedules reach the calculator, and the calculator is named once, as the one the rule asks.
        val code = sources.flatMap { code(it.readText()).lines() }.map { it.trim() }
        assertTrue(SCHEDULES_TO_THE_CALCULATOR in code)
        assertEquals(listOf(THE_RULE_ASKS_THE_CALCULATOR), code.filter { "airingAt" in it && !it.startsWith("import ") })
    }

    @Test
    fun `what carries the rule out derives nothing either, reads schedules only as a notification may, and reads no zone`() {
        val sources = sources("notify").filter { it.name in carriers }
        assertEquals(carriers, sources.map { it.name }.toSet())
        assertEquals(emptyList<String>(), sources.flatMap { carrierProblems(it.name, it.readText()) })
        val code = sources.flatMap { code(it.readText()).lines() }.map { it.trim() }
        // The schedules are read in one place and reach the rule, which is asked in the run alone.
        assertEquals(1, code.count { it == SCHEDULES_AS_A_NOTIFICATION_MAY })
        assertEquals(2, code.count { it.endsWith(THE_RUN_ASKS_THE_RULE) })
        assertEquals(2, code.count { "notificationsAt(" in it })
        val planted =
            listOf(
                "val schedules = airing.observeStoredSchedules().first()",
                "val airing = airing.observePageAiring(anime).first()",
                "val counts = airing.observeAiring().first()",
                "val stored: Map<AnimeId, StoredAiring> = store.read()",
                "val airing = airingAt(entry.anime, read.schedules[entry.anime.id], now)",
                "val due = read.schedules.filterValues { it.episodes.any { e -> e.airsAt <= now } }",
                "val schedules = airing.observeSchedules().first() - finished",
                "val aired = AiredCount.Exact(announcement.episode)",
                "val today = clock.instant().atZone(clock.zone).toLocalDate()",
                "val zone = ZoneId.systemDefault()",
                "val now = Instant.now()",
            )
        for (source in planted) assertTrue(source, carrierProblems("A.kt", source).isNotEmpty())
        assertEquals(emptyList<String>(), carrierProblems("A.kt", "val began = clock.instant()\n" + carriersHandOn.joinToString("\n")))
    }

    @Test
    fun `notifications name roles, never a service, in their code`() {
        assertEquals(emptyList<String>(), sources("notify").flatMap { serviceProblems(it.name, it.readText()) })
        val planted =
            listOf(
                "enum class Recheck { AniListAnswered }",
                "class MalRecord(val malId: Long)",
                "val anilistFailed: Boolean,",
                "fun recheck(aniList: AiringSource)",
                "val error = DataError.MalRefused",
            )
        for (source in planted) assertEquals(source, 1, serviceProblems("A.kt", source).size)
        assertEquals(emptyList<String>(), serviceProblems("A.kt", "val normal = formal.minimal // MAL's dates are Japan's"))
    }

    @Test
    fun `a planted derivation fails`() {
        val planted =
            listOf(
                "val aired = stored[id]?.episodes.orEmpty().filter { !it.airsAt.isAfter(now) }",
                "val count = schedule.episodes.count { it.airsAt <= now }",
                "val total = schedule.totalEpisodes",
                "if (schedule.doubtedOver != null) return",
                "val (episodes, total) = schedules.getValue(anime.id)",
                "val episodes = with(schedules[anime.id]) { this?.component1() }",
                "private fun peek(s: AiringSchedule) = s.let { (e, _) -> e }",
                "val airing = calculate(anime, schedules[anime.id]?.copy(doubtedOver = null), now)",
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
                "import io.github.kauri_off.kiroku.core.airing.AiringSchedule\n" +
                "    schedules: Map<AnimeId, AiringSchedule>,\n" +
                "    calculate: (Anime, AiringSchedule?, Instant) -> Airing,\n" +
                "$THE_RULE_ASKS_THE_CALCULATOR\n" +
                "        $SCHEDULES_TO_THE_CALCULATOR\n" +
                "val open = airing.airTimes.filter { it.number > watched }.map { it.airsAt.plus(delay) }\n"
        assertEquals(emptyList<String>(), notifyProblems("A.kt", clean))
    }

    @Test
    fun `the airing calculator reads no time zone but Japan's`() {
        val sources = sources("airing")
        assertTrue(sources.any { it.name == "AiringCalculator.kt" })
        assertEquals(emptyList<String>(), sources.flatMap { zoneProblems(it.name, it.readText()) })
        assertEquals(1, sources.sumOf { source -> code(source.readText()).lines().count { it.trim() == JAPAN_IS } })
        val planted =
            listOf(
                "?.atStartOfDay(ZoneId.systemDefault())",
                "?.atStartOfDay(zone)",
                "internal fun japanDay(now: Instant): LocalDate = now.atZone(clock.zone).toLocalDate()",
                "val japanNow = now.atZone(ZoneOffset.UTC)",
                "val HERE: ZoneId = ZoneId.of(\"Europe/Moscow\")",
                "val today = LocalDate.now()",
                "val today = LocalDate.ofInstant(now, zone)",
                "val zone = TimeZone.getDefault().toZoneId()",
                "val start = date.atStartOfDay().atOffset(offset)",
                "val calendar = java.util.Calendar.getInstance()",
                "val day = java.text.SimpleDateFormat(\"yyyy-MM-dd\").format(now)",
                "val text = java.util.Date().toString()",
            )
        for (source in planted) assertTrue(source, zoneProblems("A.kt", source).isNotEmpty())
        val clean =
            listOf(
                "import java.time.ZoneId",
                JAPAN_IS,
                "val japanNow = now.atZone(JAPAN)",
                "?.atStartOfDay(JAPAN)",
                "val now = clock.instant()",
            )
        assertEquals(emptyList<String>(), zoneProblems("A.kt", clean.joinToString("\n")))
    }
}

// The module's directory, wherever Gradle runs the tests from.
private val coreDir: File =
    generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }.resolve("core")

private fun sources(packageName: String): List<File> =
    File(
        coreDir,
        "src/main/kotlin/io/github/kauri_off/kiroku/core/$packageName",
    ).listFiles().orEmpty().filter { it.extension == "kt" }.sorted()

// The source without its line comments.
private fun code(source: String): String = source.lines().joinToString("\n") { it.substringBefore("//") }

private const val SCHEDULES_TO_THE_CALCULATOR = "val airing = calculate(anime, schedules[anime.id], now)"
private const val THE_RULE_ASKS_THE_CALCULATOR = "): Notifications = notificationsAt(entries, schedules, settings, record, now, ::airingAt)"
private const val JAPAN_IS = "val JAPAN: ZoneId = ZoneId.of(\"Asia/Tokyo\")"

private const val SCHEDULES_AS_A_NOTIFICATION_MAY = "val schedules = airing.observeSchedules().first()"
private const val THE_RUN_ASKS_THE_RULE =
    "result = notificationsAt(read.entries, read.schedules, read.settings, read.record, clock.instant())"

// The files that say what is notifiable, and those that carry it out.
private val rules = setOf("Notifiable.kt", "NotificationRecord.kt", "NotificationSettings.kt")
private val carriers = setOf("NotificationEngine.kt", "NotificationPorts.kt", "NotificationRuns.kt")

// The only lines of the notification rules that name the schedules or their type: they are handed on, never opened.
private val handedOn =
    setOf(
        "import io.github.kauri_off.kiroku.core.airing.AiringSchedule",
        "schedules: Map<AnimeId, AiringSchedule>,",
        "calculate: (Anime, AiringSchedule?, Instant) -> Airing,",
        THE_RULE_ASKS_THE_CALCULATOR,
        SCHEDULES_TO_THE_CALCULATOR,
    )

private val forbidden =
    mapOf(
        "reads what a schedule holds" to Regex("""\.(episodes|totalEpisodes|doubtedOver)\b"""),
        "reads the title's own airing facts" to Regex("""\.(broadcast|startDate|malEpisodes|airingStatus)\b"""),
        "builds a value of the calculator" to Regex("""\b(AiredCount\.(Exact|Estimated)|ScheduledEpisode|Airing)\("""),
        "reads the calculator's result for a screen" to Regex("""\.(latestAiredAt|nextEpisode|changesAt|premiere)\b"""),
        "reads a clock or a zone" to
            Regex(
                """\b(Clock|ZoneId|ZoneOffset|ZonedDateTime|OffsetDateTime|LocalDate|LocalDateTime|LocalTime|TimeZone)\b""" +
                    """|\.now\(\)|\.instant\(\)|currentTimeMillis""",
            ),
    )

// A line of the notification rules that derives what only the airing calculator may.
internal fun notifyProblems(
    name: String,
    source: String,
): List<String> =
    code(source).lines().flatMapIndexed { index, line ->
        val opened = Regex("""\b(schedules|AiringSchedule)\b""").containsMatchIn(line) && line.trim() !in handedOn
        val problems = forbidden.filterValues { it.containsMatchIn(line) }.keys + listOfNotNull("opens the schedules".takeIf { opened })
        problems.map { "$name:${index + 1} $it: ${line.trim()}" }
    }

// The only lines of what carries the rule out that name the schedules or their type: read as a notification may, and handed on.
private val carriersHandOn =
    setOf(
        "import io.github.kauri_off.kiroku.core.airing.AiringSchedule",
        "val schedules: Map<AnimeId, AiringSchedule>,",
        "schedules: Map<AnimeId, AiringSchedule>,",
        "airing.observeSchedules(),",
        ") { session, entries, schedules, settings -> seen(session.owner, entries, schedules, settings) }",
        ") = if (owner == null) Seen(null, emptyList(), emptyMap(), null) else Seen(owner, entries, schedules, settings)",
        "var $THE_RUN_ASKS_THE_RULE",
        THE_RUN_ASKS_THE_RULE,
        "seen = seen(read.owner, read.entries, read.schedules, read.settings)",
        SCHEDULES_AS_A_NOTIFICATION_MAY,
        "if (sessions.first().owner == owner) return Read(owner, stored.record, settings, entries, schedules)",
    )

private val forbiddenToCarriers =
    forbidden - "reads a clock or a zone" +
        mapOf(
            "reads a zone or a clock of its own" to
                Regex(
                    """\b(ZoneId|ZoneOffset|ZonedDateTime|OffsetDateTime|LocalDate|LocalDateTime|LocalTime|TimeZone)\b""" +
                        """|\.now\(\)|\.zone\b|currentTimeMillis""",
                ),
            "asks the calculator beside the rule" to Regex("""\bairingAt\b"""),
            "reads airing data a notification may not" to
                Regex(
                    """\b(observeStoredSchedules|observePageAiring|observeAiring|observeStored|AiringStore|AiringSource|StoredAiring)\b""",
                ),
        )

// A line of what carries the rule out that derives what only the calculator may, or reads what no notification may.
internal fun carrierProblems(
    name: String,
    source: String,
): List<String> =
    code(source).lines().flatMapIndexed { index, line ->
        val opened = Regex("""\b(schedules|AiringSchedule)\b""").containsMatchIn(line) && line.trim() !in carriersHandOn
        val problems =
            forbiddenToCarriers.filterValues { it.containsMatchIn(line) }.keys + listOfNotNull("opens the schedules".takeIf { opened })
        problems.map { "$name:${index + 1} $it: ${line.trim()}" }
    }

private val service = Regex("""\b(MAL|Mal[A-Z]\w*|mal[A-Z]\w*|\w*Ani[Ll]ist\w*|ani[Ll]ist\w*)\b|\.Mal[A-Z]""")

// A line of code that names a service where a role is meant.
internal fun serviceProblems(
    name: String,
    source: String,
): List<String> =
    code(source).lines().flatMapIndexed { index, line ->
        if (service.containsMatchIn(line)) listOf("$name:${index + 1} ${line.trim()}") else emptyList()
    }

private val zoneRead =
    Regex(
        """systemDefault|\bTimeZone\b|\bZoneOffset\b|\bOffsetDateTime\b|\.zone\b|getZone|withZone|atOffset""" +
            """|\b(Calendar|SimpleDateFormat|DateFormat)\b|\bDate\(""" +
            """|\b(LocalDate|LocalDateTime|LocalTime|ZonedDateTime|Year|YearMonth)\.now\(""" +
            """|\b(atZone|atStartOfDay|ofInstant)\((?!JAPAN\))""",
    )

// A line of the airing package that reads a zone other than through [JAPAN_IS].
internal fun zoneProblems(
    name: String,
    source: String,
): List<String> =
    code(source).lines().flatMapIndexed { index, line ->
        val text = line.trim()
        val defined = text == JAPAN_IS || text == "import java.time.ZoneId"
        if (zoneRead.containsMatchIn(line) || ("ZoneId" in line && !defined)) listOf("$name:${index + 1} ${line.trim()}") else emptyList()
    }
