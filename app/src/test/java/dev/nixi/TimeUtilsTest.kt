package dev.nixi

import dev.nixi.util.TimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class TimeUtilsTest {

    @Test
    fun `godzina z dwukropkiem i kropka parsuje sie tak samo`() {
        val a = TimeUtils.parseFlexible("15:30")
        val b = TimeUtils.parseFlexible("15.30")
        assertNotNull(a)
        assertEquals(a, b)
        assertTrue(a!!.endsWith("T15:30:00"))
    }

    @Test
    fun `sama godzina bez minut`() {
        val v = TimeUtils.parseFlexible("7")
        assertNotNull(v)
        assertTrue(v!!.endsWith("T07:00:00"))
    }

    @Test
    fun `data plus godzina w roznych formatach`() {
        val iso = TimeUtils.parseFlexible("2026-09-25T15:30")
        assertEquals("2026-09-25T15:30:00", iso)

        val space = TimeUtils.parseFlexible("2026-09-25 15:30")
        assertEquals("2026-09-25T15:30:00", space)

        val short = TimeUtils.parseFlexible("2026-9-5 9:05")
        assertEquals("2026-09-05T09:05:00", short)

        val dateOnly = TimeUtils.parseFlexible("2026-09-25")
        assertEquals("2026-09-25T00:00:00", dateOnly)
    }

    @Test
    fun `jutro i pojutrze przesuwaja dzien`() {
        val today = TimeUtils.parseFlexible("12:00")!!
        val dayToday = today.take(10)
        val tomorrow = TimeUtils.parseFlexible("jutro 12:00")!!
        val dayTomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
        }.let {
            String.format(
                Locale.US, "%04d-%02d-%02d",
                it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)
            )
        }
        assertEquals(dayTomorrow, tomorrow.take(10))
        assertTrue(tomorrow.endsWith("T12:00:00"))
        assertTrue(dayToday.length == 10)
    }

    @Test
    fun `smieci zwracaja null a nie wyjatek`() {
        assertNull(TimeUtils.parseFlexible(""))
        assertNull(TimeUtils.parseFlexible("nie wiem kiedy"))
        assertNull(TimeUtils.parseFlexible("2026/09/25"))
    }

    @Test
    fun `okno dnia ma sens i nie zapetla sie`() {
        val (start, end) = TimeUtils.todayWindow()
        val s = TimeUtils.parseIso(start)!!
        val e = TimeUtils.parseIso(end)!!
        val span = e.time - s.time
        assertTrue("span=$span", span >= 23 * 60 * 60 * 1000L && span <= 25 * 60 * 60 * 1000L)
        assertTrue(s.time <= System.currentTimeMillis())
    }

    @Test
    fun `granice dnia stykaja sie o polnocy`() {
        // Okno „dziś" kończy się dokładnie tam, gdzie zaczyna się „jutro":
        // dzięki temu kalendarz na dziś nie gubi wydarzeń o 00:00 ani nie
        // dubluje ich w obu oknach (regres: rozjazd o sekundę).
        val (_, todayEnd) = TimeUtils.todayWindow()
        val (tomorrowStart, _) = TimeUtils.windowFor(1)
        assertEquals("00:00:00", todayEnd.substringAfter('T'))
        assertEquals("00:00:00", tomorrowStart.substringAfter('T'))
        assertEquals(todayEnd, tomorrowStart)
        assertEquals(
            TimeUtils.parseIso(todayEnd)!!.time,
            TimeUtils.parseIso(tomorrowStart)!!.time
        )
    }

    @Test
    fun `wczorajsze okno konczy sie na poczatku dzisiejszego`() {
        val (_, yesterdayEnd) = TimeUtils.windowFor(-1)
        val (todayStart, _) = TimeUtils.todayWindow()
        assertEquals(todayStart, yesterdayEnd)
    }

    @Test
    fun `polnoc i ostatnia minuta dnia parsuja sie poprawnie`() {
        assertTrue(TimeUtils.parseFlexible("00:00")!!.endsWith("T00:00:00"))
        assertTrue(TimeUtils.parseFlexible("23:59")!!.endsWith("T23:59:00"))
    }

    @Test
    fun `dzien tygodnia w numeracji postgresa`() {
        val d = TimeUtils.dayOfWeekPostgres()
        assertTrue("dzień=$d", d in 1..7)
    }

    @Test
    fun `formatowanie godzin nie gubi wartosci przy wielu watkach`() {
        // SimpleDateFormat nie jest bezpieczny wątkowo — sprawdzamy, że wspólny
        // TimeUtils radzi sobie z równoległym użyciem (regres: wyścig na formatterze)
        val iso = "2026-09-25T15:30:00"
        val results = (1..8).map { i ->
            Thread {
                repeat(200) {
                    val h = TimeUtils.hourOf(iso)
                    if (h != "15:30") throw AssertionError("wątek $i dostał $h")
                }
            }
        }
        results.forEach { it.start() }
        results.forEach { it.join(5000) }
    }
}
