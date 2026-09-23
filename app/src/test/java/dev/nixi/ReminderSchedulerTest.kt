package dev.nixi

import dev.nixi.notif.ReminderScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderSchedulerTest {

    @Test
    fun `iso bez strefy jest czytane lokalnie`() {
        val t = ReminderScheduler.parseIso("2026-09-25T15:30:00")
        assertNotNull(t)
        val local = java.util.Calendar.getInstance().apply { timeInMillis = t!! }
        assertEquals(15, local.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(25, local.get(java.util.Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `iso z Z jest czytane jako UTC`() {
        val t = ReminderScheduler.parseIso("2026-09-25T15:30:00Z")
        assertNotNull(t)
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = t!!
        }
        assertEquals(15, utc.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `iso z milisekundami i offsetem`() {
        assertNotNull(ReminderScheduler.parseIso("2026-09-25T15:30:00.123+02:00"))
    }

    @Test
    fun `smieci zwracaja null`() {
        assertNull(ReminderScheduler.parseIso(""))
        assertNull(ReminderScheduler.parseIso("kiedyś"))
    }

    @Test
    fun `wiersz przypomnienia ma poprawny format`() {
        val row = ReminderScheduler.reminderRow(0L, "test")
        assertEquals("test", row.optString("title"))
        assertNotNull(ReminderScheduler.parseIso(row.optString("fires_at")))
    }
}
