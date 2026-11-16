package io.github.kauri_off.kiroku.wiring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit

object DeviceClock : Clock() {
    override fun instant(): Instant = Instant.now()

    override fun millis(): Long = System.currentTimeMillis()

    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)
}

// The time since the device started, its deep sleep included, so that a hold measured in it ends when it says.
internal object DeviceTime : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    override fun read(): Long = SystemClock.elapsedRealtimeNanos()
}

// Emits when the device's clock or its time zone is set, which no wait sees; a receiver is held only while it is collected.
internal fun deviceClockSet(context: Context): Flow<Unit> =
    callbackFlow {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    trySend(Unit)
                }
            }
        val app = context.applicationContext
        val set = IntentFilter().apply { CLOCK_SET.forEach(::addAction) }
        ContextCompat.registerReceiver(app, receiver, set, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { app.unregisterReceiver(receiver) }
    }.conflate()

internal val CLOCK_SET = listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
