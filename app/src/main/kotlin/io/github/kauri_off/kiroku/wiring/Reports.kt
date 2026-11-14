package io.github.kauri_off.kiroku.wiring

import android.util.Log
import io.github.kauri_off.kiroku.notShown

private val UNEXPECTED = notShown("Unexpected: ")
private val STANDS_FOR = notShown("Stands for: ")
private val CAUSED_BY = notShown("Caused by: ")
private val FRAME = notShown("\tat ")
private const val CAUSES = 8

// What is said of an exception where its message, which could quote the list or an answer, must not go: one line each
// for its type, for the type its message names when it names one, for its frames, and the same for its causes.
internal fun typesOf(e: Throwable): List<String> =
    buildList {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth++ < CAUSES) {
            add((if (isEmpty()) UNEXPECTED else CAUSED_BY) + current.javaClass.name)
            current.message?.takeIf(::namesAType)?.let { add(STANDS_FOR + it) }
            current.stackTrace.mapTo(this) { FRAME + it }
            val cause = current.cause
            current = if (cause === current) null else cause
        }
    }

// The report of a release build: its log gets the types, one line each, and the app goes on.
internal fun logTypes(e: Throwable) = typesOf(e).forEach { Log.e(LOG_TAG, it) }

// True for the name of a class of this app, as the network's and the session's reports carry in place of a message.
private fun namesAType(message: String): Boolean =
    try {
        Class.forName(message, false, Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader())
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
