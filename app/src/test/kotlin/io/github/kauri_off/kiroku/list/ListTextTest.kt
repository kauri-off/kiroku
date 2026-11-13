package io.github.kauri_off.kiroku.list

import android.provider.Settings
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.text.AirTimeText
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
    @Config(qualifiers = "ru")
    fun `russian lines follow the glossary`() {
        val text = text()
        assertEquals("Просмотрено 12\u00A0из\u00A024", text.line2(Line2.Progress(12, 24)))
        assertEquals("Просмотрено 212", text.line2(Line2.Progress(212, null)))
        assertEquals("Просмотрено 25\u00A0из\u00A024", text.line2(Line2.Progress(25, 24)))
        assertEquals("К просмотру:\u00A0~2", text.line3(Line3.ToWatch(AiredCount.Estimated(2))))
        assertEquals("Серия\u00A015 ·\u00A0сб\u00A017:30", text.line3(Line3.NextEpisode(ScheduledEpisode(15, at(17, 17, 30)))))
        assertEquals("Премьера сб\u00A017:30", text.premiere(Premiere.At(at(17, 17, 30))))
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
        assertEquals("Watched 12\u00A0of\u00A024", text.line2(Line2.Progress(12, 24)))
        assertEquals("Watched 212", text.line2(Line2.Progress(212, null)))
        assertEquals("Watched 25\u00A0of\u00A024", text.line2(Line2.Progress(25, 24)))
        assertEquals("~2\u00A0to\u00A0watch", text.line3(Line3.ToWatch(AiredCount.Estimated(2))))
        assertEquals("Premieres Sat\u00A017:30", text.premiere(Premiere.At(at(17, 17, 30))))
        assertEquals("2\u00A0to\u00A0watch", text.line3(Line3.ToWatch(AiredCount.Exact(2))))
        assertEquals("Premieres January\u00A02027", text.premiere(Premiere.InMonth(YearMonth.of(2027, 1))))
        assertEquals("Premieres\u00A02027", text.premiere(Premiere.InYear(Year.of(2027))))
        assertEquals("Not scored", text.line2(Line2.Score(null)))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `russian line 2 is followed by the value the tab is sorted by, after a dot in lower case`() {
        val text = text()
        val progress = Line2.Progress(12, 24)
        assertEquals("Просмотрено 12\u00A0из\u00A024 ·\u00A0оценка:\u00A08", text.line2(progress, SortedBy.Score(8)))
        assertEquals("Просмотрено 12\u00A0из\u00A024 ·\u00A0без\u00A0оценки", text.line2(progress, SortedBy.Score(null)))
        assertEquals("Просмотрено 12\u00A0из\u00A024 ·\u00A02025", text.line2(progress, SortedBy.StartYear(2025)))
    }

    @Test
    @Config(qualifiers = "en")
    fun `english line 2 is followed by the score or the start year`() {
        val text = text()
        val progress = Line2.Progress(12, 24)
        assertEquals("Watched 12\u00A0of\u00A024", text.line2(progress, null))
        assertEquals("Watched 12\u00A0of\u00A024 ·\u00A0Score\u00A08", text.line2(progress, SortedBy.Score(8)))
        assertEquals("Watched 12\u00A0of\u00A024 ·\u00A0Not\u00A0scored", text.line2(progress, SortedBy.Score(null)))
        assertEquals("Watched 12\u00A0of\u00A024 ·\u00A02025", text.line2(progress, SortedBy.StartYear(2025)))
        assertEquals("Score\u00A08 ·\u00A02024", text.line2(Line2.Score(8), SortedBy.StartYear(2024)))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the list writes air times exactly as the title page does`() {
        val context = RuntimeEnvironment.getApplication()
        val times = AirTimeText(context, now, zone)
        listOf(at(14, 18), at(15, 0, 30), at(17, 17, 30), at(21, 9)).forEach { instant ->
            val episode = ScheduledEpisode(15, instant)
            assertEquals(times.nextEpisode(episode), text().line3(Line3.NextEpisode(episode)))
            assertEquals(times.premiere(instant), text().premiere(Premiere.At(instant)))
        }
    }
}
