package io.github.kauri_off.kiroku.wiring

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone

class DeviceClockTest {
    private val original = TimeZone.getDefault()

    @After
    fun restore() {
        TimeZone.setDefault(original)
    }

    @Test
    fun `the device clock follows a change of the time zone while the app runs`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        assertEquals(ZoneId.of("Asia/Tokyo"), DeviceClock.zone)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Anchorage"))
        assertEquals(ZoneId.of("America/Anchorage"), DeviceClock.zone)
    }
}
