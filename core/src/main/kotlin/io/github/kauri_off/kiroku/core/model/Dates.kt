package io.github.kauri_off.kiroku.core.model

import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

sealed interface PartialDate {
    val year: Int

    data class Day(
        val date: LocalDate,
    ) : PartialDate {
        override val year: Int get() = date.year
    }

    data class Month(
        val yearMonth: YearMonth,
    ) : PartialDate {
        override val year: Int get() = yearMonth.year
    }

    data class OnlyYear(
        val value: Year,
    ) : PartialDate {
        override val year: Int get() = value.value
    }
}

// The first day of the period the date names.
fun PartialDate.firstDay(): LocalDate =
    when (this) {
        is PartialDate.Day -> date
        is PartialDate.Month -> yearMonth.atDay(1)
        is PartialDate.OnlyYear -> value.atDay(1)
    }

// The last day of the period the date names.
fun PartialDate.lastDay(): LocalDate =
    when (this) {
        is PartialDate.Day -> date
        is PartialDate.Month -> yearMonth.atEndOfMonth()
        is PartialDate.OnlyYear -> value.atMonth(12).atEndOfMonth()
    }

enum class Season { Winter, Spring, Summer, Fall }

data class AnimeSeason(
    val year: Int,
    val season: Season,
) {
    companion object {
        fun of(date: LocalDate): AnimeSeason = AnimeSeason(date.year, Season.entries[(date.monthValue - 1) / 3])
    }
}
