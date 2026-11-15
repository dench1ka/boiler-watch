package ru.dacha.boiler

import java.util.Calendar
import java.util.TimeZone

object Schedule {
    fun parse(input: String): List<Pair<Int, Int>> {
        val items = input.split(",").map { it.trim() }
        require(items.size in 1..24) { "Укажите от 1 до 24 времён через запятую" }
        return items.map {
            require(Regex("[0-2][0-9]:[0-5][0-9]").matches(it)) { "Время указывается как 09:00, 18:30" }
            val h = it.substring(0, 2).toInt()
            require(h < 24) { "Час должен быть от 00 до 23" }
            h to it.substring(3).toInt()
        }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    }
    fun next(input: String, now: Long, zone: TimeZone = TimeZone.getDefault()): Long = parse(input).minOf { (h, m) ->
        Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            }
        }.timeInMillis
    }
}
