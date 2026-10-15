package tv.own.owntv.core.theme

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month

/**
 * glcn: the season the app dresses up for, when the user turned seasonal touches on (Settings →
 * Appearance). Halloween is all of October, Christmas runs from 1 December to 6 January (Reyes
 * included), New Year's Eve takes over on the 31st from 18:00 to the small hours, Carnival covers
 * the weekend before Shrove Tuesday, and Valentine's the four days up to the 14th.
 */
enum class Season { NONE, HALLOWEEN, CHRISTMAS, NEW_YEAR, CARNIVAL, VALENTINE }

object Seasons {

    fun current(now: LocalDateTime = LocalDateTime.now()): Season {
        val d = now.toLocalDate()
        val newYearsEve = (d.month == Month.DECEMBER && d.dayOfMonth == 31 && now.hour >= 18) ||
            (d.month == Month.JANUARY && d.dayOfMonth == 1 && now.hour < 4)
        return when {
            newYearsEve -> Season.NEW_YEAR
            d.month == Month.OCTOBER -> Season.HALLOWEEN
            d.month == Month.DECEMBER -> Season.CHRISTMAS
            d.month == Month.JANUARY && d.dayOfMonth <= 6 -> Season.CHRISTMAS
            d.month == Month.FEBRUARY && d.dayOfMonth in 10..14 -> Season.VALENTINE
            isCarnival(d) -> Season.CARNIVAL
            else -> Season.NONE
        }
    }

    /** Saturday to Shrove Tuesday (47 days before Easter Sunday). */
    private fun isCarnival(d: LocalDate): Boolean {
        val tuesday = easter(d.year).minusDays(47)
        return !d.isBefore(tuesday.minusDays(3)) && !d.isAfter(tuesday)
    }

    /** Easter Sunday (Gregorian, anonymous algorithm). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    /** A small sign of the season beside the greeting. */
    fun mark(season: Season): String? = when (season) {
        Season.HALLOWEEN -> "🎃"
        Season.CHRISTMAS -> "🎄"
        Season.NEW_YEAR -> "🥂"
        Season.CARNIVAL -> "🎭"
        Season.VALENTINE -> "❤️"
        Season.NONE -> null
    }
}
