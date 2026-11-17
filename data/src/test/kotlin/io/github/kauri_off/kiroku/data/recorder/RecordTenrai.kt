package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.tenrai.TenraiStatistics
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.system.exitProcess

// The build's Tenrai recorder (section 11.1): its arguments are where the answers go and where a run's answers wait.
fun main(arguments: Array<String>) {
    val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.cache"
    exitProcess(
        recordTenrai(
            only = System.getenv("KIROKU_RECORD_ONLY").orEmpty(),
            into = File(arguments[0]),
            staging = File(arguments[1]),
            lock = File(cache, "kiroku/tenrai-recorder.lock"),
        ),
    )
}

// The process's exit code.
internal fun recordTenrai(
    only: String,
    into: File,
    staging: File,
    lock: File,
    address: HttpUrl = TenraiStatistics.ADDRESS.toHttpUrl(),
    calls: Call.Factory = tenraiCalls(),
    today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    say: (String) -> Unit = ::println,
    pause: () -> Unit = { Thread.sleep(TENRAI_PAUSE_MILLIS) },
): Int {
    val recorder = TenraiRecorder(calls, address, into, staging, today, say, pause)
    val failed =
        try {
            alone(lock) { recorder.record(chosenOfTenrai(only)) }
            null
        } catch (stop: RecorderStop) {
            stop.message
        } catch (e: Exception) {
            "The recorder failed: ${e.javaClass.name}"
        }
    say("Requests sent to Tenrai: ${recorder.sent}")
    failed?.let(say)
    return if (failed == null) 0 else 1
}

// The whole list, or the requests named in [only], separated by commas.
internal fun chosenOfTenrai(only: String): List<TenraiAsk> {
    val names = only.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return TenraiRequests
    names.firstOrNull { name -> TenraiRequests.none { it.name == name } }?.let { throw RecorderStop("no request is named $it") }
    return TenraiRequests.filter { it.name in names }
}
