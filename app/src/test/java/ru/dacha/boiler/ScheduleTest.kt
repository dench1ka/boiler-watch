package ru.dacha.boiler

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Calendar
import java.util.Random

class ScheduleTest {
    private val zone = TimeZone.getTimeZone("Europe/Minsk")
    private fun time(value: String, tz: TimeZone = zone): Long = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = tz }.parse(value)!!.time
    @Test fun nextSlotToday() {
        assertEquals(time("2026-11-15 18:30"), Schedule.next("09:00, 18:30", time("2026-11-15 10:00"), zone))
    }
    @Test fun equalTimeSchedulesTomorrow() {
        assertEquals(time("2026-11-16 09:00"), Schedule.next("09:00", time("2026-11-15 09:00"), zone))
    }
    @Test fun rollsOverYear() {
        assertEquals(time("2027-01-01 00:01"), Schedule.next("00:01", time("2026-12-31 23:59"), zone))
    }
    @Test fun sortsAndDeduplicates() {
        assertEquals(listOf(9 to 0, 18 to 30), Schedule.parse("18:30, 09:00, 09:00"))
    }
    @Test fun springTransitionKeepsNextDayWallClock() {
        val berlin = TimeZone.getTimeZone("Europe/Berlin")
        assertEquals(time("2026-03-29 09:00", berlin), Schedule.next("09:00", time("2026-03-28 10:00", berlin), berlin))
    }
    @Test fun autumnTransitionKeepsNextDayWallClock() {
        val berlin = TimeZone.getTimeZone("Europe/Berlin")
        assertEquals(time("2026-10-25 09:00", berlin), Schedule.next("09:00", time("2026-10-24 10:00", berlin), berlin))
    }
    @Test fun handlesLeapDay() {
        assertEquals(time("2028-02-29 09:00"), Schedule.next("09:00", time("2028-02-28 23:59"), zone))
    }
    @Test fun oneMillisecondBeforeSlotStillSelectsIt() {
        val slot = time("2026-11-15 09:00")
        assertEquals(slot, Schedule.next("09:00", slot - 1, zone))
    }
    @Test fun nextSlotIsFutureAndUsesConfiguredWallClockAcrossZones() {
        val random = Random(17)
        for (zoneName in listOf("UTC", "Europe/Minsk", "Europe/Berlin", "Asia/Kolkata")) {
            val tz = TimeZone.getTimeZone(zoneName)
            repeat(100) {
                val now = time("2026-01-01 00:00", tz) + (random.nextDouble() * 365 * 24 * 3600_000L).toLong()
                val next = Schedule.next("09:00, 18:30", now, tz)
                assertTrue(next > now)
                assertTrue(next - now <= 26 * 3600_000L)
                val calendar = Calendar.getInstance(tz).apply { timeInMillis = next }
                assertTrue((calendar.get(Calendar.HOUR_OF_DAY) to calendar.get(Calendar.MINUTE)) in listOf(9 to 0, 18 to 30))
                assertEquals(0, calendar.get(Calendar.SECOND))
                assertEquals(0, calendar.get(Calendar.MILLISECOND))
            }
        }
    }
    @Test fun rejectsInvalidTimes() {
        listOf("", "24:00", "9:00", "09:60", "09:00,", "-1:00").forEach {
            try { Schedule.parse(it); fail("Accepted invalid time: $it") } catch (_: IllegalArgumentException) { }
        }
    }
}
