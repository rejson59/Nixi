package dev.nixi.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.TelephonyManager
import dev.nixi.audio.MediaPauseController
import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.util.TimeUtils
import org.json.JSONObject

/** Narzędzia systemowe: czas, bateria, aplikacje, multimedia. */
object SystemTools {

    fun now(): ToolResult {
        val ctx = ToolContext.app
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = if (android.os.Build.VERSION.SDK_INT >= 21) bm.intProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) else -1
        val charging = when {
            android.os.Build.VERSION.SDK_INT >= 21 ->
                bm.intProperty(BatteryManager.BATTERY_PROPERTY_STATUS) in
                    intArrayOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
            else -> false
        }
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val data = try {
            tm.dataState == TelephonyManager.DATA_CONNECTED
        } catch (_: Exception) {
            true
        }
        ToolResult.ok(
            "Data: ${TimeUtils.fullNow()}. Bateria: ${level}%${if (charging) " (ładowanie)" else ""}. " +
                "Internet: ${if (data) "tak" else "brak (WiFi sprawdzaj osobno)" }."
        )
    }

    fun mediaPause(): ToolResult {
        val n = MediaPauseController.pauseAll()
        return if (n > 0) ToolResult.ok("Zatrzymałam odtwarzanie (aplikacji: $n).")
        else ToolResult.ok("Nie znalazłam aktywnej muzyki — nic nie puszczasz.")
    }

    fun mediaResume(): ToolResult {
        MediaPauseController.resumeAll()
        return ToolResult.ok("Wznowiłam odtwarzanie.")
    }

    /** Otwiera aplikację po polskiej angielskiej nazwie lub pakiecie. */
    fun openApp(query: String): ToolResult {
        val ctx = ToolContext.app
        val pm = ctx.packageManager
        val q = query.trim().lowercase()
        if (q.isBlank()) return ToolResult.fail("Podaj nazwę aplikacji.")

        // 1) dokładnie jako pakiet
        val byPkg = pm.getLaunchIntentForPackage(q)
        if (byPkg != null) {
            byPkg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(byPkg)
            return ToolResult.ok("Otworzyłam $q.")
        }

        // 2) wyszukiwanie po nazwie aplikacji (startowe)
        var best: Pair<String, String>? = null // (score, label)
        var bestPkg = ""
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (a in apps) {
            val label = pm.getApplicationLabel(a).toString().lowercase()
            if (label.isEmpty()) continue
            val score = score(label, q)
            if (score > 0 && (best == null || score > best.first)) {
                best = score to label
                bestPkg = a.packageName
            }
        }
        if (bestPkg.isNotEmpty()) {
            val intent = pm.getLaunchIntentForPackage(bestPkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                return ToolResult.ok("Otworzyłam „${best!!.second}”.")
            }
        }
        return ToolResult.fail("Nie znalazłam aplikacji „$query”.")
    }

    private fun score(label: String, q: String): Int {
        if (label == q) return 100
        if (label.startsWith(q)) return 80
        if (label.contains(q)) return 50
        // tolerancja na pojedynczy błąd liter
        if (levenshtein(label, q) <= 2 && q.length > 3) return 30
        return 0
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** Profil użytkownika (users) — odczyt. */
    fun userProfile(): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny.")
        val row = runCatching { SupabaseHub.c().listRows(Tables.USER, limit = 1).rows.firstOrNull() }
            .getOrNull()
        if (row == null) return ToolResult.fail("Brak profilu użytkownika w tabeli users.")
        val name = row.optString("display_name", row.optString("name", ""))
        return ToolResult.ok("Profil: $name. ${row.toString().take(600)}")
    }

    fun updateProfile(row: JSONObject): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny.")
        val keep = JSONObject()
        listOf("display_name", "name", "birth_date", "notes", "schedule_notes", "email", "city")
            .forEach { if (row.has(it)) keep.put(it, row.get(it)) }
        if (keep.length() == 0) return ToolResult.fail("Podaj pola do zmiany (np. notes).")
        val r = runCatching {
            val upd = SupabaseHub.c().update(Tables.USER, mapOf("id" to "eq.1"), keep)
            if (upd.ok) upd else SupabaseHub.c().insert(Tables.USER, keep.put("id", 1))
        }
        return r.getOrNull()?.let {
            if (it.ok) ToolResult.ok("Zaktualizowałam profil: ${keep.keys().asSequence().joinToString(", ") { it }}.")
            else ToolResult.fail("Błąd zapisu: ${it.error}")
        } ?: ToolResult.fail("Błąd: Supabase")
    }

    fun isListenerEnabled(): Boolean {
        val ctx = ToolContext.app
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(ctx.packageName)
    }
}
