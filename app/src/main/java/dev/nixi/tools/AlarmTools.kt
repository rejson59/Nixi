package dev.nixi.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.notif.ActionNotifier
import dev.nixi.notif.ReminderReceiver
import org.json.JSONObject

/**
 * Budziki — systemowe (intent AlarmClock) + lustrzana tabela `alarms`
 * w Supabase (NIXI zna swoje ustawione alarmy: czas, etykieta, dźwięk).
 *
 * Uwaga: Android nie udostępnia publicznego API odczytu/usuwania alarmów
 * z zegara systemowego — NIXI zarządza alarmami, które sama ustawiła
 * (lista z tabeli + ponowne ustawienie intentem; do skasowania ostatniego
 * otwiera listę alarmów z zaznaczeniem którego).
 */
object AlarmTools {

    suspend fun list(): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny.")
        val rows = runCatching {
            SupabaseHub.c().listRows(Tables.ALARMS, mapOf("active" to "eq.true"), orderBy = "time.asc", limit = 30)
        }.getOrNull()
        if (rows == null || !rows.ok) return ToolResult.ok("Brak ustawionych budzików.")
        if (rows.rows.isEmpty()) return ToolResult.ok("Nie masz ustawionych budzików.")
        val items = rows.rows.joinToString("\n") {
            "- ${it.optString("time")}" +
                (it.optString("label").ifBlank { null } ?: "").let { l -> if (l.isNotBlank()) " ($l)" else "" } +
                (if (it.optBoolean("ring_on_weekends", true)) "" else " (dni robocze)")
        }
        return ToolResult.ok("Twoje budziki:\n$items")
    }

    /** time: "HH:mm"; label; days: "weekdays"|"weekend"|"daily"; sound: nazwa/id dźwięku. */
    suspend fun add(time: String, label: String, days: String, sound: String): ToolResult {
        val ctx = ToolContext.app
        val h = time.substringBefore(':').toIntOrNull() ?: return ToolResult.fail("Zły czas „$time”.")
        val m = time.substringAfter(':').toIntOrNull() ?: return ToolResult.fail("Zły czas „$time”.")
        if (h !in 0..23 || m !in 0..59) return ToolResult.fail("Zły czas „$time”.")

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (days == "weekdays") putExtra(AlarmClock.EXTRA_DAYS, intArrayOf(1, 2, 3, 4, 5))
            ringtoneExtra(sound)
            if (Build.VERSION.SDK_INT >= 31) putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }

        // lustro w bazie
        val row = JSONObject().apply {
            put("time", "%02d:%02d".format(h, m))
            put("label", label)
            put("ring_on_weekends", days != "weekdays")
            put("sound", sound)
            put("active", true)
            put("created_by_nixi", true)
            // created_at wypełnia baza (timestamptz default now())
        }
        val id: Long? = if (SupabaseHub.available) {
            val r = runCatching { SupabaseHub.c().insert(Tables.ALARMS, row) }.getOrNull()
            r?.rows?.firstOrNull()?.let {
                it.optLong("id", 0).takeIf { v -> v > 0 } ?: it.optLong("created", 0).takeIf { v -> v > 0 }
            } ?: r?.rows?.firstOrNull()?.optLong("id")
        } else null

        ActionNotifier.notify(
            ctx, "NIXI: budzik",
            "Nowy alarm ${"%02d:%02d".format(h, m)}${if (label.isNotBlank()) " — $label" else ""}",
            short = true
        )
        return ToolResult.ok(
            "Ustawiłam alarm ${"%02d:%02d".format(h, m)}${if (label.isNotBlank()) " („$label”)" else ""}." +
                (if (Build.VERSION.SDK_INT >= 31) "" else " Potwierdź go w otwartym zegarze.")
        )
    }

    /** Edycja alarmu: ponowne ustawienie intentem + aktualizacja lustra. */
    suspend fun edit(id: String, time: String, label: String, sound: String, active: String): ToolResult {
        if (id.isBlank()) return ToolResult.fail("Podaj id alarmu (z listy).")
        val ctx = ToolContext.app
        val row = JSONObject()
        time.takeIf { it.isNotBlank() }?.let {
            val h = it.substringBefore(':').toIntOrNull() ?: return ToolResult.fail("Zły czas.")
            val m = it.substringAfter(':').toIntOrNull() ?: return ToolResult.fail("Zły czas.")
            row.put("time", "%02d:%02d".format(h, m))
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h)
                putExtra(AlarmClock.EXTRA_MINUTES, m)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                if (sound.isNotBlank()) ringtoneExtra(sound)
                if (Build.VERSION.SDK_INT >= 31) putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { ctx.startActivity(intent) }
        }
        label.takeIf { it.isNotBlank() }?.let { row.put("label", it) }
        sound.takeIf { it.isNotBlank() }?.let { row.put("sound", it) }
        active.takeIf { it.isNotBlank() }?.let { row.put("active", it.lowercase() == "true" || it == "1") }
        if (row.length() == 0) return ToolResult.fail("Brak pól do edycji.")
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny.")
        val r = runCatching { SupabaseHub.updateRow(Tables.ALARMS, mapOf("id" to "eq.$id"), row) }.getOrNull()
            ?: return ToolResult.fail("Błąd edycji.")
        if (!r.ok) return ToolResult.fail("Błąd edycji: ${r.error}")
        ActionNotifier.notify(ctx, "NIXI: budzik", "Edycja alarmu #$id.", short = true)
        return ToolResult.ok("Zaktualizowałam alarm #$id.")
    }

    /**
     * Dźwięk alarmu — intent przyjmuje Uri (content://...), więc dodajemy
     * extra tylko dla poprawnych URI; nazwę i tak trzymamy w lustrze.
     */
    private fun Intent.ringtoneExtra(sound: String) {
        val s = sound.trim()
        if (s.isBlank()) return
        val uri = try {
            val u = android.net.Uri.parse(s)
            if (u.scheme != null && u.host != null || s.startsWith("content://")) u else null
        } catch (_: Exception) {
            null
        }
        if (uri != null) putExtra(AlarmClock.EXTRA_RINGTONE, uri)
    }

    /** Usuwanie: wygaszenie w lustrze + otwarcie listy alarmów (system nie daje API deleta). */
    suspend fun remove(id: String): ToolResult {
        if (id.isBlank()) return ToolResult.fail("Podaj id alarmu.")
        val ctx = ToolContext.app
        val r = if (SupabaseHub.available) {
            runCatching {
                SupabaseHub.updateRow(Tables.ALARMS, mapOf("id" to "eq.$id"),
                    JSONObject().put("active", false))
            }.getOrNull()
        } else null
        // pokaż listę, żeby użytkownik zdjął go w zegarze (transparensja!)
        runCatching {
            ctx.startActivity(
                Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        ActionNotifier.notify(
            ctx, "NIXI: budzik",
            "Alarm #$id wyłączony (w NIXI). Otworzyłam listę alarmów, aby zdjąć go w zegarze.",
            short = true
        )
        return ToolResult.ok(
            "Wyłączyłam alarm #$id po swojej stronie i otworzyłam listę alarmów telefonu, " +
                "żebyś zdjął go w zegarze (Android nie daje zewnętrznego API usuwania)."
        )
    }
}
