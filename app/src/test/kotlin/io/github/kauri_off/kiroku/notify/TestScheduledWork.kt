package io.github.kauri_off.kiroku.notify

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Scheduled work for one test's application, gone at the test's end (AppEnds): a real WorkManager would outlive it, bound to
// that application, for every later test of the JVM.
object TestScheduledWork {
    // [tasks] runs scheduled work's own tasks (its records), for a test that holds them; by default they run at once.
    fun install(
        context: Context,
        tasks: Executor? = null,
    ) = WorkManagerTestInitHelper.initializeTestWorkManager(
        context,
        Configuration
            .Builder()
            .setExecutor(SynchronousExecutor())
            .apply { tasks?.let(::setTaskExecutor) }
            .setMinimumLoggingLevel(Log.WARN)
            .build(),
    )

    // The periodic work under [name] as scheduled now.
    fun periodic(
        context: Context,
        name: String,
    ): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    // Runs the work as the system would at its next time, its constraints met.
    fun run(
        context: Context,
        work: WorkInfo,
    ) = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)).run {
        setAllConstraintsMet(work.id)
        setInitialDelayMet(work.id)
    }

    // Waits for work still running, whose end reads the work's store, then lets the test's WorkManager go with its store open.
    @SuppressLint("RestrictedApi")
    fun end() {
        if (!WorkManager.isInitialized()) return
        val work = WorkManagerImpl.getInstance()!!
        val limit = TimeSource.Monotonic.markNow() + WAIT
        while (work.getWorkInfos(WorkQuery.fromStates(WorkInfo.State.RUNNING)).get().isNotEmpty() && limit.hasNotPassedNow()) {
            Thread.sleep(PAUSE)
        }
        WorkManagerImpl.setDelegate(null)
    }

    // Scheduled work on the system's job scheduler, as the app sets it up, for a test of what the system does to its jobs; the
    // test ends it with [died].
    fun onTheSystem(context: Context) = WorkManager.initialize(context, Configuration.Builder().setMinimumLoggingLevel(Log.WARN).build())

    // The process's scheduled work gone with it: its store closed, and nothing of it left for the next to find.
    @SuppressLint("RestrictedApi")
    fun died() {
        if (!WorkManager.isInitialized()) return
        WorkManagerImpl.getInstance()?.closeDatabase()
        forgetLeaked()
    }

    // A WorkManager the app made for itself is a static nothing clears: cleared here, so that only the test that made it fails.
    @SuppressLint("RestrictedApi")
    fun forgetLeaked() {
        WorkManagerImpl.setDelegate(null)
        WorkManagerImpl::class.java
            .getDeclaredField(DEFAULT)
            .apply { isAccessible = true }
            .set(null, null)
    }

    private val WAIT = 20.seconds
    private const val PAUSE = 20L
    private const val DEFAULT = "sDefaultInstance"
}
