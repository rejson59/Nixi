package dev.nixi.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.notif.ActionNotifier
import dev.nixi.notif.ReminderReceiver
import dev.nixi.util.TimeUtils
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Przypomnienia (tabela reminders + dokładny AlarmManager). */
object ReminderTools {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale("pl"))

    suspend fun list(): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny.")
        val rows = runCatching {
            SupabaseHub.c().listRows(Tables.REMINDERS, mapOf("done" to "eq.false"),
                orderBy = "fires_at.asc", limit = 20)
        }.getOrNull()
        if (rows == null || !rows.ok) return ToolResult.ok("Brak przypomnień.")
        if (rows.rows.isEmpty()) return ToolResult.ok("Nie masz aktywnych przypomnień.")
        val items = rows.rows.joinToString("\n") {
            val t = it.optString("fires_at")
            val h = t.takeLast(8).substring(0, 5).replace('T', ':')
            val d = t.take(10)
            "- $d $h: ${it.optString("title")}"
        }
        return ToolResult.ok("Przypomnienia:\n$items")
    }

    /** when: "2026-09-25T15:30", "15:30" (dziś), "jutro 8:00" itp. */
    suspend fun add(title: String, whenIso: String): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny.")
        if (title.isBlank()) return ToolResult.fail("Podaj treść przypomnienia.")

        val iso = parseWhen(whenIso) ?: return ToolResult.fail(
            "Nie rozumiem czasu „$whenIso” (np. 17:30, jutro 8:00, 2026-09-25T17:30)."
        )
        val firesAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }.parse(iso) ?: return ToolResult.fail("Błąd czasu.")
        if (firesAt.time < System.currentTimeMillis()) {
            return ToolResult.fail("Ten czas jest już w przeszłości.")
        }

        val row = JSONObject().apply {
            put("title", title)
            put("fires_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = java.util.TimeZone.getDefault()
            }.format(firesAt))
            put("done", false)
            put("created_at", System.currentTimeMillis())
        }
        val ins = runCatching { SupabaseHub.c().insert(Tables.REMINDERS, row) }.getOrNull()
        if (ins == null || !ins.ok) return ToolResult.fail("Błąd zapisu: ${ins?.error}")

        val id = ins.rows.firstOrNull()?.optLong("id", 0)
        if (id > 0) scheduleAlarm(ToolContext.app, id, firesAt.time, title)

        ActionNotifier.notify(
            ToolContext.app, "NIXI: przypomnienie",
            "$title — ${fmt.format(firesAt)}", short = true
        )
        return ToolResult.ok("Zaplanowałam: $title (${fmt.format(firesAt)}).")
    }

    suspend fun done(id: String): ToolResult {
        if (id.isBlank()) return ToolResult.fail("Podaj id.")
        val r = runCatching {
            SupabaseHub.updateRow(Tables.REMINDERS, mapOf("id" to "eq.$id"), JSONObject().put("done", true))
        }.getOrNull()
        return if (r?.ok == true) ToolResult.ok("Oznaczono jako wykonane.")
        else ToolResult.fail("Nie znalazłam przypomnienia #$id.")
    }

    suspend fun remove(id: String): ToolResult {
        if (id.isBlank()) return ToolResult.fail("Podaj id.")
        cancelAlarm(ToolContext.app, id.toLongOrNull() ?: 0L)
        val r = runCatching {
            SupabaseHub.deleteRows(Tables.REMINDERS, mapOf("id" to "eq.$id"))
        }.getOrNull()
        return if (r?.ok == true) ToolResult.ok("Usunięte.")
        else ToolResult.fail("Nie znalazłam przypomnienia #$id.")
    }

    // ── AlarmManager ──────────────────────────────────────────────────────

    internal fun scheduleAlarm(ctx: Context, id: Long, atMillis: Long, title: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_ID, id)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
        }
        val pi = PendingIntent.getBroadcast(
            ctx, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            val canExact = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (t: Throwable) {
            ActionNotifier.notify(
                ctx, "NIXI: przypomnienie",
                "System nie pozwolił na dokładny alarm — przypomnienie „$title” może przyjść z opóźnieniem.",
                short = true
            )
        }
    }

    internal fun cancelAlarm(ctx: Context, id: Long) {
        if (id <= 0) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, ReminderReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, id.toInt(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { pi?.let { am.cancel(it) } }
    }

    /** "17:30" / "17:30:00" / "jutro 8:00" / "2026-09-25 17:30" / ISO */
    private fun parseWhen(s: String): String? {
        val t = s.trim().lowercase()
        return try {
            val base = java.util.Calendar.getInstance()
            val hms = Regex("""(\d{1,2})[:.](\d{2})""").find(t)
            if (hms == null) return null
            val h = hms.groupValues[1].toInt()
            val m = hms.groupValues[2].toInt()
            var dayOffset = 0
            if (t.contains("jutro")) dayOffset = 1
            else if (t.contains("przeszło") || t.contains("wczoraj")) dayOffset = -1
            else {
                val dateMatch = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(t)
                if (dateMatch != null) {
                    base.clear()
                    base.set(dateMatch.groupValues[1].toInt(), dateMatch.groupValues[2].toInt() - 1,
                        dateMatch.groupValues[3].toInt(), h, m, 0)
                } else if (!t.contains("dzis")) {
                    // "8:00" bez daty => dziś; jeśli już minęło => jutro
                    base.set(h, m, 0)
                    if (base.timeInMillis < System.currentTimeMillis()) {
                        base.add(java.util.Calendar.DAY_OF_MONTH, 1)
                    }
                }
            }
            if (dayOffset != 0) base.add(java.util.Calendar.DAY_OF_MONTH, dayOffset)
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = java.util.TimeZone.getDefault()
            }.format(base.time)
        } catch (_: Exception) {
            null
        }
    }
}
