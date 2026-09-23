package dev.nixi.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Pomocnice czasu / dat (ISO-8601 dla Supabase, lokalnie dla UI). */
object TimeUtils {

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val isoZ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val hourFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val fullFmt = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("pl"))

    fun isoNow(): String {
        iso.timeZone = TimeZone.getDefault()
        return iso.format(java.util.Date())
    }

    /** Dzisiaj 00:00 -> jutro 00:00 (lokalnie). */
    fun todayWindow(): Pair<String, String> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.time
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.time
        iso.timeZone = TimeZone.getDefault()
        return Pair(iso.format(start), iso.format(end))
    }

    fun windowFor(dayOffset: Int): Pair<String, String> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.time
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.time
        iso.timeZone = TimeZone.getDefault()
        return Pair(iso.format(start), iso.format(end))
    }

    /** "HH:mm" (lokalnie) + dziś => ISO z godziną; jeśli podano pełną datę, zwraca ją. */
    fun parseFlexible(s: String): String? {
        val t = s.trim()
        return try {
            when {
                t.contains("T") -> {
                    if (t.endsWith("Z")) {
                        // UTC -> lokalnie
                        val cal = Calendar.getInstance()
                        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        df.timeZone = TimeZone.getTimeZone("UTC")
                        cal.time = df.parse(t)!!
                        iso.timeZone = TimeZone.getDefault()
                        iso.format(cal.time)
                    } else t
                }
                Regex("""^\d{1,2}[:.]\d{2}$""").matches(t) -> {
                    val h = t.substringBefore(':').substringBefore('.').toInt()
                    val m = t.substringAfter(':').substringAfter('.').toInt()
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, h)
                    cal.set(Calendar.MINUTE, m)
                    iso.timeZone = TimeZone.getDefault()
                    iso.format(cal.time)
                }
                Regex("""^\d{1,2}$""").matches(t) -> {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, t.toInt())
                    iso.timeZone = TimeZone.getDefault()
                    iso.format(cal.time)
                }
                Regex("""^\d{4}-\d{2}-\d{2}$""").matches(t) -> "${t}T00:00:00"
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun hourNow(): String {
        hourFmt.timeZone = TimeZone.getDefault()
        return hourFmt.format(java.util.Date())
    }

    fun fullNow(): String {
        fullFmt.timeZone = TimeZone.getDefault()
        return fullFmt.format(java.util.Date())
    }

    /** 1=pon .. 7=ndz (postawa PostgreSQL'owa). */
    fun dayOfWeekPostgres(): Int {
        return (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    }

    fun dayNamePl(day: Int): String = when (day) {
        1 -> "poniedziałek"; 2 -> "wtorek"; 3 -> "środa"; 4 -> "czwartek"
        5 -> "piątek"; 6 -> "sobota"; 7 -> "niedziela"; else -> "?"
    }

    fun dayNameFromToday(offset: Int): String =
        dayNamePl((dayOfWeekPostgres() + offset - 1) % 7 + 1)

    fun dateOf(isoStr: String): String? = try {
        dayFmt.timeZone = TimeZone.getDefault()
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        df.timeZone = TimeZone.getDefault()
        dayFmt.format(df.parse(isoStr.trim()) ?: java.util.Date())
    } catch (_: Exception) {
        null
    }

    fun hourOf(isoStr: String): String? = try {
        hourFmt.timeZone = TimeZone.getDefault()
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        df.timeZone = TimeZone.getDefault()
        hourFmt.format(df.parse(isoStr.trim()) ?: java.util.Date())
    } catch (_: Exception) {
        null
    }
}
