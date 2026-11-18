package io.github.kauri_off.kiroku.notificationlog

import android.util.Log
import androidx.annotation.VisibleForTesting
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.notify.Recheck
import io.github.kauri_off.kiroku.core.notify.RunEnd
import io.github.kauri_off.kiroku.core.notify.RunReport
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.wiring.LOG_TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// An episode a run posted, with the title's name as it was then.
data class LoggedPost(
    val anime: AnimeId,
    val title: String,
    val episode: Int,
    val airedAt: Instant,
    val notifyAt: Instant,
    val postedAt: Instant,
    val shown: Boolean,
) {
    // From the notification's time to its post, both by the device's clock.
    val delay: Duration get() = Duration.between(notifyAt, postedAt)
}

// What the debug menu keeps of a run of the notification engine.
data class LoggedRun(
    val trigger: RunTrigger,
    val began: Instant,
    val ended: Instant,
    val end: RunEnd,
    val recheck: Recheck,
    val posted: List<LoggedPost>,
    val withdrawn: List<AnimeId>,
    val alarm: Instant?,
    val alarmFailed: Boolean,
) {
    companion object {
        fun of(report: RunReport) =
            LoggedRun(
                report.trigger,
                report.began,
                report.ended,
                report.end,
                report.recheck,
                report.posted.map {
                    LoggedPost(it.anime.id, it.anime.titles.romaji, it.episode, it.airedAt, it.notifyAt, it.postedAt, it.shown)
                },
                report.withdrawn.sortedBy { it.value },
                report.alarm,
                report.alarmFailed,
            )
    }
}

// The latest runs of one source, newest first: in memory, and in [file] when it has one, which only [files] reads and writes.
class RunLog(
    private val file: File?,
    private val limit: Int = LIMIT,
    private val files: Executor = shared,
    // Each run as one line of the device's log as well, where a build that cannot be debugged is read from.
    private val log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) : (RunReport) -> Unit {
    private val kept = MutableStateFlow(emptyList<LoggedRun>())
    val runs: StateFlow<List<LoggedRun>> = kept.asStateFlow()

    // Runs in the file, which grows by appending until it holds twice the limit.
    private var inFile = 0

    init {
        if (file != null) {
            files.execute {
                val stored = read(file)
                synchronized(this) { kept.value = (kept.value + stored.asReversed()).take(limit) }
                inFile = stored.size
            }
        }
    }

    override fun invoke(report: RunReport) {
        val run = LoggedRun.of(report)
        synchronized(this) { kept.value = (listOf(run) + kept.value).take(limit) }
        log(line(run))
        if (file != null) files.execute { store(run, file) }
    }

    // Returns once every run kept before it is in the file.
    @VisibleForTesting
    fun stored() {
        val done = CountDownLatch(1)
        files.execute(done::countDown)
        check(done.await(WAIT_SECONDS, TimeUnit.SECONDS))
    }

    private fun store(
        run: LoggedRun,
        file: File,
    ) {
        try {
            file.appendText(written(run))
            if (++inFile > limit * 2) {
                val runs = read(file)
                val next = File(file.path + NEXT)
                next.writeText(runs.joinToString("", transform = ::written))
                if (next.renameTo(file)) inFile = runs.size else next.delete()
            }
        } catch (_: IOException) {
        }
    }

    // A file or a run that cannot be read gives no run; the device's log still has each one.
    private fun read(file: File): List<LoggedRun> =
        try {
            if (!file.exists()) {
                emptyList()
            } else {
                (LINE + file.readText())
                    .split(LINE + RUN)
                    .drop(1)
                    .mapNotNull(::parsed)
                    .takeLast(limit)
            }
        } catch (_: IOException) {
            emptyList()
        }

    companion object {
        const val LIMIT = 500
        private const val WAIT_SECONDS = 10L
        private val shared: Executor =
            Executors.newSingleThreadExecutor {
                Thread(
                    it,
                    notShown("notification-runs"),
                ).apply { isDaemon = true }
            }
        private val NEXT = notShown(".next")
        private val NONE = notShown("-")
        private val SPACE = notShown(" ")
        private val LINE = notShown("\n")
        private val RUN = notShown("run ")
        private val POST = notShown("post ")
        private val WITHDRAWN = notShown("withdrawn ")
        private val FAILED = notShown(" failed")
        private val NOT_SHOWN = notShown(" not shown")

        // A run as lines of words: the run, each title it withdrew, then each post with the title's name ending its line.
        private fun written(run: LoggedRun): String =
            buildString {
                with(
                    run,
                ) { append(RUN + listOf(trigger, began, ended, end, recheck, alarm ?: NONE, alarmFailed).joinToString(SPACE) + LINE) }
                run.withdrawn.forEach { append(WITHDRAWN + it.value + LINE) }
                run.posted.forEach {
                    val words =
                        listOf(
                            it.anime.value,
                            it.episode,
                            it.airedAt,
                            it.notifyAt,
                            it.postedAt,
                            it.shown,
                            it.title.lines().joinToString(SPACE),
                        )
                    append(POST + words.joinToString(SPACE) + LINE)
                }
            }

        private fun parsed(block: String): LoggedRun? =
            try {
                val lines = block.lines().filter { it.isNotEmpty() }
                val words = lines.first().split(SPACE)
                LoggedRun(
                    RunTrigger.valueOf(words[0]),
                    Instant.parse(words[1]),
                    Instant.parse(words[2]),
                    RunEnd.valueOf(words[3]),
                    Recheck.valueOf(words[4]),
                    lines.filter { it.startsWith(POST) }.map { post(it.removePrefix(POST)) },
                    lines.filter { it.startsWith(WITHDRAWN) }.map { AnimeId(it.removePrefix(WITHDRAWN).toLong()) },
                    words[5].takeIf { it != NONE }?.let(Instant::parse),
                    words[6].toBooleanStrict(),
                )
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            } catch (_: NoSuchElementException) {
                null
            } catch (_: DateTimeException) {
                null
            }

        private fun post(line: String): LoggedPost {
            val words = line.split(SPACE, limit = POST_WORDS)
            return LoggedPost(
                AnimeId(words[0].toLong()),
                words[6],
                words[1].toInt(),
                Instant.parse(words[2]),
                Instant.parse(words[3]),
                Instant.parse(words[4]),
                words[5].toBooleanStrict(),
            )
        }

        private const val POST_WORDS = 7

        // One line for the device's log, without the title's name, which is the list's.
        fun line(run: LoggedRun): String {
            val took = Duration.between(run.began, run.ended).toMillis()
            val posted =
                run.posted.map {
                    notShown("${it.anime.value}:${it.episode} +${it.delay.toMillis()} ms") +
                        (if (it.shown) "" else NOT_SHOWN)
                }
            val withdrawn = run.withdrawn.map { it.value }
            val alarm = (run.alarm ?: NONE).toString() + (if (run.alarmFailed) FAILED else "")
            return notShown(
                "Notification run: ${run.trigger} ${run.began} $took ms ${run.end} re-check ${run.recheck}; posted $posted; withdrawn $withdrawn; alarm $alarm",
            )
        }
    }
}
