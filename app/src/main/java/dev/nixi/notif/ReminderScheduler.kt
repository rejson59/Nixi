package dev.nixi.notif

import android.content.Context
import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.tools.ReminderTools
import dev.nixi.util.LogBus
import org.json.JSONObject

/**
 * Odtwarzanie alarmów przypomnień po restarcie telefonu / aktualizacji aplikacji.
 *
 * AlarmManager czyści wszystkie alarmy przy restarcie urządzenia, a HyperOS
 * potrafi je skasować także po „wymuszeniu zatrzymania”. Bez tego kroku
 * przypomnienia ustawione przez NIXI po prostu nie dzwoniły.
 *
 * Trwałe źródło prawdy to tabela `reminders` w Supabase — dlatego po restarcie
 * odtwarzamy z niej wszystko, co ma `done = false` i czas w przyszłości
 * (albo niedawno minął).
 */
object ReminderScheduler {

    private const val OVERDUE_GRACE_MS = 24 * 60 * 60 * 1000L
    private const val OVERDUE_FIRE_DELAY_MS = 5_000L

    @Volatile private var lastRun = 0L

    /** Odtwarza alarmy z bazy. [force] = ignoruj throttling (np. po restarcie). */
    suspend fun rescheduleAll(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < 30 * 60 * 1000L) return
        lastRun = now
        if (!SupabaseHub.available) return
        val r = runCatching {
            SupabaseHub.c().listRows(
                Tables.REMINDERS,
                mapOf("done" to "eq.false"),
                orderBy = "fires_at.asc",
                limit = 50
            )
        }.getOrNull() ?: return
        if (!r.ok) {
            LogBus.log("reminder.reschedule", "nie mogę odczytać reminders: ${r.error}", "warn")
            return
        }
        var scheduled = 0
        for (row in r.rows) {
            val iso = row.optString("fires_at")
            if (iso.isBlank()) continue
            val id = row.optLong("id", 0L)
            if (id <= 0) continue
            val at = parseIso(iso) ?: continue
            val title = row.optString("title").ifBlank { "Przypomnienie" }
            when {
                at > now -> {
                    ReminderTools.scheduleAlarm(context, id, at, title)
                    scheduled++
                }
                now - at < OVERDUE_GRACE_MS -> {
                    // spóźnione (np. restart w nocy) — przypomnij zaraz
                    ReminderTools.scheduleAlarm(context, id, now + OVERDUE_FIRE_DELAY_MS, title)
                    scheduled++
                }
                else -> Unit // zbyt stare — zostaje w tabeli do wglądu
            }
        }
        LogBus.log("reminder.reschedule", "odtworzono $scheduled alarmów")
    }

    /** Tolerancyjne parsowanie ISO (z/bez strefy, z/bez sekund). */
    fun parseIso(iso: String): Long? {
        val t = iso.trim()
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
        )
        for (p in patterns) {
            val f = java.text.SimpleDateFormat(p, java.util.Locale.US)
            if (p.contains("XXX")) f.timeZone = java.util.TimeZone.getDefault()
            else if (p.endsWith("'Z'")) f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            else f.timeZone = java.util.TimeZone.getDefault()
            val parsed = runCatching { f.parse(t) }.getOrNull() ?: continue
            if (parsed != null) return parsed.time
        }
        return null
    }

    fun reminderRow(firesAtMillis: Long, title: String): JSONObject = JSONObject().apply {
        put("title", title)
        put("fires_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getDefault() }
            .format(java.util.Date(firesAtMillis)))
    }
}
