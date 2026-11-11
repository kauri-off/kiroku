package io.github.kauri_off.kiroku.list

import android.provider.Settings
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class ListTextTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val now = LocalDateTime.of(2026, 1, 14, 12, 0).atZone(zone).toInstant()

    private fun text(hours24: Boolean = true): ListText {
        val context = RuntimeEnvironment.getApplication()
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, if (hours24) "24" else "12")
        return ListText(context, now, zone)
    }

    private fun at(
        day: Int,
        hour: Int,
        minute: Int = 0,
    ) = LocalDateTime.of(2026, 1, day, hour, minute).atZone(zone).toInstant()

    @Test
    @Config(qualifiers = "en")
    fun `an air time is today, tomorrow, a weekday within six days, then a date`() {
        val text = text()
        assertEquals("today\u00A018:00", text.airTime(at(14, 18)))
        assertEquals("tomorrow\u00A000:30", text.airTime(at(15, 0, 30)))
        assertEquals("Sat\u00A017:30", text.airTime(at(17, 17, 30)))
        assertEquals("Tue\u00A009:00", text.airTime(at(20, 9)))
        assertEquals("Jan 21, 09:00", text.airTime(at(21, 9)))
    }

    @Test
    @Config(qualifiers = "en")
    fun `an air time follows the twelve hour setting`() {
        assertEquals("Sat\u00A05:30 PM", text(hours24 = false).airTime(at(17, 17, 30)))
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
    @Config(qualifiers = "ru")
    fun `russian lines follow the glossary`() {
        val text = text()
        assertEquals("12\u00A0/\u00A024 ·\u00A0вышло\u00A014", text.line2(Line2.Progress(12, 24, AiredCount.Exact(14))))
        assertEquals("12 ·\u00A0вышло\u00A0~14", text.line2(Line2.Progress(12, null, AiredCount.Estimated(14))))
        assertEquals("К просмотру:\u00A0~2", text.line3(Line3.ToWatch(AiredCount.Estimated(2))))
        assertEquals("Серия\u00A015 ·\u00A0сб\u00A017:30", text.line3(Line3.NextEpisode(ScheduledEpisode(15, at(17, 17, 30)))))
        assertEquals("Премьера: сб\u00A017:30", text.premiere(Premiere.At(at(17, 17, 30))))
        assertEquals("Премьера 4 апр.", text.premiere(Premiere.OnDay(LocalDate.of(2026, 4, 4))))
        assertEquals("Премьера в\u00A0январе\u00A02027\u00A0г.", text.premiere(Premiere.InMonth(YearMonth.of(2027, 1))))
        assertEquals("Премьера в\u00A02027\u00A0г.", text.premiere(Premiere.InYear(Year.of(2027))))
        assertEquals("Ещё не вышло", text.premiere(Premiere.Unannounced))
        assertEquals("Оценка:\u00A08", text.line2(Line2.Score(8)))
        assertEquals("Без оценки", text.line2(Line2.Score(null)))
    }

    @Test
    @Config(qualifiers = "en")
    fun `english lines follow the glossary`() {
        val text = text()
        assertEquals("12\u00A0/\u00A024", text.line2(Line2.Progress(12, 24, null)))
        assertEquals("12\u00A0/\u00A024 ·\u00A0~14\u00A0aired", text.line2(Line2.Progress(12, 24, AiredCount.Estimated(14))))
        assertEquals("2\u00A0to watch", text.line3(Line3.ToWatch(AiredCount.Exact(2))))
        assertEquals("Premieres January\u00A02027", text.premiere(Premiere.InMonth(YearMonth.of(2027, 1))))
        assertEquals("Premieres\u00A02027", text.premiere(Premiere.InYear(Year.of(2027))))
        assertEquals("Not scored", text.line2(Line2.Score(null)))
    }
}
