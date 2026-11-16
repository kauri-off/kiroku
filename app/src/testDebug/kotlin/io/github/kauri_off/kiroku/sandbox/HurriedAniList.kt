package io.github.kauri_off.kiroku.sandbox

import io.github.kauri_off.kiroku.wiring.DeviceTime
import org.junit.rules.ExternalResource
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.DurationUnit

// The sandbox's AniList client on a time that is an hour later at each look, so that no spacing of it costs a test real seconds.
class HurriedAniList : ExternalResource() {
    override fun before() {
        SandboxGraph.aniListTime = Hurried
    }

    override fun after() {
        SandboxGraph.aniListTime = DeviceTime
    }

    private object Hurried : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
        private val now = AtomicLong()

        override fun read(): Long = now.addAndGet(1.hours.inWholeNanoseconds)
    }
}
