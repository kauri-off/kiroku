package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.network.MalHosts
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import kotlin.system.exitProcess

private const val PAUSE_MILLIS = 2000L

// The build's recorder (section 11.1): its arguments are where the answers go and where a run's answers wait.
fun main(arguments: Array<String>) {
    val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.cache"
    exitProcess(
        recordMal(System.getenv(), into = File(arguments[0]), staging = File(arguments[1]), lock = File(cache, "kiroku/recorder.lock")),
    )
}

// The process's exit code. The client id is [environment]'s KIROKU_RECORD_CLIENT_ID and nothing else.
internal fun recordMal(
    environment: Map<String, String>,
    into: File,
    staging: File,
    lock: File,
    hosts: MalHosts = MalHosts.Real,
    say: (String) -> Unit = ::println,
    pause: () -> Unit = { Thread.sleep(PAUSE_MILLIS) },
): Int {
    fun given(name: String) = environment[name].orEmpty().trim()
    val recorder =
        Recorder(
            http = recorderHttp(hosts, given("KIROKU_RECORD_CLIENT_ID")),
            users =
                mapOf(
                    RecordedUser.Public to given("KIROKU_RECORD_PUBLIC_USER"),
                    RecordedUser.Private to given("KIROKU_RECORD_PRIVATE_USER"),
                ),
            into = into,
            staging = staging,
            say = say,
            pause = pause,
        )
    val failed =
        try {
            alone(lock) {
                if (given("KIROKU_RECORD_CLIENT_ID").isEmpty()) throw RecorderStop(NO_CLIENT_ID)
                recorder.record(chosen(given("KIROKU_RECORD_ONLY")))
            }
            null
        } catch (stop: RecorderStop) {
            stop.message
        } catch (e: Exception) {
            // An exception's message may quote an address, and with it a name.
            "The recorder failed: ${e.javaClass.name}"
        }
    say("Requests sent to MAL: ${recorder.sent}")
    failed?.let(say)
    return if (failed == null) 0 else 1
}

// One recorder at a time for everything that shares [lock], so requests from two worktrees never overlap.
internal fun <T> alone(
    lock: File,
    run: () -> T,
): T {
    lock.parentFile.mkdirs()
    RandomAccessFile(lock, "rw").channel.use { channel ->
        val held =
            try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } ?: throw RecorderStop("another recorder is running, nothing was asked")
        return held.use { run() }
    }
}

// The whole list, or the requests named in [only], separated by commas.
internal fun chosen(only: String): List<Ask> {
    val names = only.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return ClientIdRequests
    names.firstOrNull { name -> ClientIdRequests.none { it.name == name } }?.let { throw RecorderStop("no request is named $it") }
    return ClientIdRequests.filter { it.name in names }
}
