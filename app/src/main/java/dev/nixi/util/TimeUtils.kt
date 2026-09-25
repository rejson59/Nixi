package dev.nixi.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pomocnice czasu / dat (ISO-8601 dla Supabase, lokalnie dla UI).
 *
 * Thread-safety: `SimpleDateFormat` NIE jest bezpieczny wątkowo, a narzędzia
 * NIXI chodzą równolegle na Dispatchers.IO. Każdy formatter żyje we
 * własnym [ThreadLocal], więc nie ma już wyścigów i „przestawionych” dat.
 */
object TimeUtils {

    private val iso = threadLocal("yyyy-MM-dd'T'HH:mm:ss")
    private val isoFull = threadLocal("yyyy-MM-dd'T'HH:mm:ss.SSS")
    private val dayFmt = threadLocal("yyyy-MM-dd")
    private val hourFmt = threadLocal("HH:mm")
    private val fullFmt = threadLocal("EEEE, d MMMM yyyy, HH:mm", Locale("pl"))

    private fun threadLocal(pattern: String, locale: Locale = Locale.US): ThreadLocal<SimpleDateFormat> =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat(pattern, locale).apply { timeZone = TimeZone.getDefault() }
        }

    private fun fmt(tl: ThreadLocal<SimpleDateFormat>): SimpleDateFormat =
        tl.get()!!.apply { timeZone = TimeZone.getDefault() }

    fun isoNow(): String = fmt(iso).format(Date())

    /** Dzisiaj 00:00 -> jutro 00:00 (lokalnie). */
    fun todayWindow(): Pair<String, String> = windowFor(0)

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
        return fmt(iso).format(start) to fmt(iso).format(end)
    }

    /**
     * Elastyczne wejście od modelu/użytkownika:
     *  „15:30”, „15.30”, „15”, „jutro 8:00”, „dziś 17:45”, „pojutrze 9:00”,
     *  „2026-09-25”, „2026-09-25 15:30”, „2026-09-25T15:30(:ss)”, ISO z „Z”.
     * Zwraca ISO w czasie lokalnym (bez strefy) albo null.
     */
    fun parseFlexible(s: String): String? {
        val raw = s.trim()
        if (raw.isEmpty()) return null
        val t = raw.lowercase(Locale.ROOT)

        // 1) ISO ze strefa (Z / +02:00) -> konwertujemy na czas lokalny
        if (Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?(\.\d+)?(Z|[+-]\d{2}:?\d{2})""")
                .containsMatchIn(raw)
        ) {
            parseAny(raw, ZONED_PATTERNS)?.let { return fmt(iso).format(it) }
        }

        // 2) data (+ opcjonalnie godzina), tolerancyjnie: „2026-9-5 9:05", „2026-09-25"
        val dt = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2})[:.](\d{2})(?::(\d{2}))?)?$""")
            .find(raw)
        if (dt != null) {
            return try {
                val cal = Calendar.getInstance()
                cal.clear()
                cal.set(
                    dt.groupValues[1].toInt(),
                    (dt.groupValues[2].toInt() - 1).coerceIn(0, 11),
                    dt.groupValues[3].toInt().coerceIn(1, 31),
                    dt.groupValues[4].ifBlank { "0" }.toInt().coerceIn(0, 23),
                    dt.groupValues[5].ifBlank { "0" }.toInt().coerceIn(0, 59),
                    dt.groupValues[6].ifBlank { "0" }.toInt().coerceIn(0, 59)
                )
                fmt(iso).format(cal.time)
            } catch (_: Exception) {
                null
            }
        }

        // 2b) „za 20 minut", „za 2 godziny"
        val rel = Regex("""za\s+(\d+)\s*(minut\w*|min|godzin\w*|godz|h)\b""").find(t)
        if (rel != null) {
            val n = rel.groupValues[1].toInt()
            val unit = rel.groupValues[2]
            val cal = Calendar.getInstance()
            if (unit.startsWith("min")) cal.add(Calendar.MINUTE, n)
            else cal.add(Calendar.HOUR_OF_DAY, n)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return fmt(iso).format(cal.time)
        }

        // 3) „jutro 8:00", „pojutrze 9:30", „dziś 17:45"
        val dayOffset = when {
            t.contains("pojutrze") -> 2
            t.contains("jutro") -> 1
            t.contains("dziś") || t.contains("dzis") || t.contains("dzisiaj") -> 0
            else -> null
        }
        if (dayOffset != null) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, dayOffset)
            val timeMatch = Regex("""(\d{1,2})[:.](\d{2})""").find(t)
            if (timeMatch != null) {
                cal.set(Calendar.HOUR_OF_DAY, timeMatch.groupValues[1].toInt().coerceIn(0, 23))
                cal.set(Calendar.MINUTE, timeMatch.groupValues[2].toInt().coerceIn(0, 59))
            } else {
                cal.set(Calendar.HOUR_OF_DAY, 9)
                cal.set(Calendar.MINUTE, 0)
            }
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return fmt(iso).format(cal.time)
        }

        // 4) sama godzina: „15:30", „15.30", „15"
        val timeMatch = Regex("""^(\d{1,2})[:.](\d{2})$""").find(t)
        val hourOnly = Regex("""^\d{1,2}$""").matches(t)
        if (timeMatch != null || hourOnly) {
            val cal = Calendar.getInstance()
            cal.set(
                Calendar.HOUR_OF_DAY,
                (if (hourOnly) t.toInt() else timeMatch!!.groupValues[1].toInt()).coerceIn(0, 23)
            )
            cal.set(
                Calendar.MINUTE,
                if (hourOnly) 0 else timeMatch!!.groupValues[2].toInt().coerceIn(0, 59)
            )
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return fmt(iso).format(cal.time)
        }
        return null
    }

    private val ZONED_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
    )

    /** Parsuje ISO w wielu wariantach (z/bez strefy). */
    fun parseIso(s: String): Date? = parseAny(s.trim(), ZONED_PATTERNS)

    private fun parseAny(value: String, patterns: List<String>): Date? {
        for (p in patterns) {
            val f = SimpleDateFormat(p, Locale.US)
            f.timeZone = when {
                p.contains("XXX") -> TimeZone.getDefault()
                p.endsWith("'Z'") -> TimeZone.getTimeZone("UTC")
                else -> TimeZone.getDefault()
            }
            val d = runCatching { f.parse(value) }.getOrNull()
            if (d != null) return d
        }
        return null
    }

    /** „2026-9-5T9:05” -> „2026-09-05T09:05:00” (PostgREST lubi stały format). */
    private fun normalizeDatePart(value: String): String {
        val m = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})T(\d{1,2}):(\d{2})(?::(\d{2}))?""").find(value)
            ?: return value
        val g = m.groupValues
        return "%s-%02d-%02dT%02d:%02d:%02d".format(
            g[1], g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(),
            g[6].ifBlank { "0" }.toInt()
        )
    }

    fun hourNow(): String = fmt(hourFmt).format(Date())

    fun fullNow(): String = fmt(fullFmt).format(Date())

    /** 1=pon .. 7=ndz (postawa PostgreSQL'owa). */
    fun dayOfWeekPostgres(): Int =
        (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1

    fun dayNamePl(day: Int): String = when (day) {
        1 -> "poniedziałek"; 2 -> "wtorek"; 3 -> "środa"; 4 -> "czwartek"
        5 -> "piątek"; 6 -> "sobota"; 7 -> "niedziela"; else -> "?"
    }

    fun dayNameFromToday(offset: Int): String =
        dayNamePl((dayOfWeekPostgres() + offset - 1) % 7 + 1)

    fun dateOf(isoStr: String): String? =
        parseIso(isoStr)?.let { fmt(dayFmt).format(it) }

    fun hourOf(isoStr: String): String? =
        parseIso(isoStr)?.let { fmt(hourFmt).format(it) }

    /** Pełny, czytelny znacznik do odpowiedzi głosowych (bez wyjątków). */
    fun human(isoStr: String): String {
        val d = parseIso(isoStr) ?: return isoStr
        return "${fmt(dayFmt).format(d)} ${fmt(hourFmt).format(d)}"
    }

    /** ISO z „pełnym” znacznikiem (ms) — do created_at. */
    fun isoMillisNow(): String = fmt(isoFull).format(Date())
}
