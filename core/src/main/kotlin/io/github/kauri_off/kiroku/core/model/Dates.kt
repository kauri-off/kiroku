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

enum class Season { Winter, Spring, Summer, Fall }

data class AnimeSeason(
    val year: Int,
    val season: Season,
)
