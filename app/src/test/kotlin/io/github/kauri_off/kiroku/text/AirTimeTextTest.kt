package io.github.kauri_off.kiroku.text

import android.provider.Settings
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class AirTimeTextTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val now = LocalDateTime.of(2026, 1, 14, 12, 0).atZone(zone).toInstant()

    private fun text(
        hours24: Boolean = true,
        zone: ZoneId = this.zone,
    ): AirTimeText {
        val context = RuntimeEnvironment.getApplication()
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, if (hours24) "24" else "12")
        return AirTimeText(context, now, zone)
    }

    private fun at(
        day: Int,
        hour: Int,
        minute: Int = 0,
        zone: ZoneId = this.zone,
    ) = LocalDateTime.of(2026, 1, day, hour, minute).atZone(zone).toInstant()

    @Test
    @Config(qualifiers = "en")
    fun `an air time is today, tomorrow, a weekday within six days, then a date`() {
        val text = text()
        assertEquals("today\u00A018:00", text.airTime(at(14, 18)))
        assertEquals("today\u00A023:59", text.airTime(at(14, 23, 59)))
        assertEquals("tomorrow\u00A000:00", text.airTime(at(15, 0)))
        assertEquals("tomorrow\u00A023:59", text.airTime(at(15, 23, 59)))
        assertEquals("Fri\u00A000:00", text.airTime(at(16, 0)))
        assertEquals("Tue\u00A023:59", text.airTime(at(20, 23, 59)))
        assertEquals("Jan 21, 00:00", text.airTime(at(21, 0)))
    }

    @Test
    @Config(qualifiers = "en")
    fun `an air time follows the twelve hour setting`() {
        val text = text(hours24 = false)
        assertEquals("today\u00A06:00 PM", text.airTime(at(14, 18)))
        assertEquals("Sat\u00A05:30 PM", text.airTime(at(17, 17, 30)))
        assertEquals("Jan 21, 9:00 AM", text.airTime(at(21, 9)))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the russian air time is in lower case and never has a weekday and a date`() {
        val text = text()
        assertEquals("сегодня\u00A018:00", text.airTime(at(14, 18)))
        assertEquals("завтра\u00A000:30", text.airTime(at(15, 0, 30)))
        assertEquals("сб\u00A017:30", text.airTime(at(17, 17, 30)))
        assertEquals("21 янв., 09:00", text.airTime(at(21, 9)))
    }

    @Test
    @Config(qualifiers = "en")
    fun `days and times are those of the given zone, not the device default`() {
        val default = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val tokyo = ZoneId.of("Asia/Tokyo")
            // 12:00 in Moscow is 18:00 in Tokyo, so 00:30 the next Tokyo day is tomorrow there.
            val text = text(zone = tokyo)
            assertEquals("today\u00A023:30", text.airTime(at(14, 23, 30, tokyo)))
            assertEquals("tomorrow\u00A000:30", text.airTime(at(15, 0, 30, tokyo)))
        } finally {
            TimeZone.setDefault(default)
        }
    }

    @Test
    @Config(qualifiers = "ru")
    fun `a next episode is written in one form`() {
        assertEquals("Серия\u00A015 ·\u00A0сб\u00A017:30", text().nextEpisode(ScheduledEpisode(15, at(17, 17, 30))))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `a premiere at a known time has no colon`() {
        assertEquals("Премьера сб\u00A017:30", text().premiere(at(17, 17, 30)))
    }

    @Test
    @Config(qualifiers = "en")
    fun `an english next episode`() {
        assertEquals("Episode\u00A015 ·\u00A0today\u00A018:00", text().nextEpisode(ScheduledEpisode(15, at(14, 18))))
    }
}
