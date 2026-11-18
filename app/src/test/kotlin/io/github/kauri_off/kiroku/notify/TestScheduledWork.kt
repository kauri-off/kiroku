package io.github.kauri_off.kiroku.notify

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

// Scheduled work for one test's application, gone at the test's end (AppEnds): a real WorkManager would outlive it, bound to
// that application, for every later test of the JVM.
object TestScheduledWork {
    fun install(context: Context) =
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setMinimumLoggingLevel(Log.WARN)
                .build(),
        )

    // The periodic work as scheduled now.
    fun periodic(context: Context): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(PeriodicWork.NAME).get()

    // Runs the work as the system would at its next time.
    fun run(
        context: Context,
        work: WorkInfo,
    ) = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setInitialDelayMet(work.id)

    @SuppressLint("RestrictedApi")
    fun end() {
        if (!WorkManager.isInitialized()) return
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }
}
