package dev.nixi.notif

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.nixi.NixiApp
import dev.nixi.db.SupabaseHub
import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Czytanie powiadomień telefonu:
 *  1. na żądanie — narzędzie notifications_read (Lista aktywnych),
 *  2. ciche reakcje — reguły z silent_rules (np. "zastępstwo z dziennika
 *     => cicho zmienia wpis w kalendarzu") + krótkie powiadomienie o tym.
 */
class NixiNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: NixiNotificationListener? = null

        fun isEnabled(context: android.content.Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return enabled.contains(context.packageName)
        }
    }

    private val rules = mutableListOf<JSONObject>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        NixiApp.scope.launch { refreshRules() }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (rules.isEmpty()) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val body = "$title $text"
        if (body.isBlank()) return

        for (rule in rules) {
            if (!rule.optBoolean("active", true)) continue
            val pkg = rule.optString("app_package")
            if (pkg.isNotBlank() && pkg != sbn.packageName) continue
            val contains = rule.optString("contains").lowercase()
            if (contains.isNotBlank() && !body.lowercase().contains(contains)) continue

            NixiApp.scope.launch {
                runCatching { applyRule(rule, sbn.packageName, title, text) }
                    .onFailure { LogBus.log("rule.err", it.message ?: "?", "error") }
            }
        }
    }

    /** Zastosuj regułę: na razie wspierany typ akcji = calendar_substitution. */
    private suspend fun applyRule(rule: JSONObject, pkg: String, title: String, text: String) {
        val action = rule.optJSONObject("action") ?: return
        val type = action.optString("type")
        val app = dev.nixi.NixiApp.ctx()

        when (type) {
            "calendar_substitution" -> {
                val subject = action.optString("subject", text.trim()).ifBlank { title.trim() }
                if (subject.isBlank()) return
                val (isoStart, isoEnd) = dev.nixi.util.TimeUtils.todayWindow()
                val events = SupabaseHub.calendarEventsBetween(isoStart, isoEnd)
                var changed = 0
                for (e in events) {
                    val eTitle = e.optString("title").lowercase()
                    val eKind = e.optString("kind", "normal")
                    if (eKind == "zastepstwo") continue
                    // dopasuj po przedmiocie (lub pierwszy event, jeśli reguła celuje "dzień")
                    val subj = subject.lowercase()
                    val match = subj.isNotEmpty() &&
                        (eTitle.contains(subj) || subj.contains(eTitle) ||
                            (subj.length > 3 && eTitle.contains(subj.take(6))))
                    if (!match) continue
                    val row = JSONObject().apply {
                        put("kind", "zastepstwo")
                        put("title", "Zastępstwo: $subject")
                        put("notes", (e.optString("notes", "") + " [auto NIXI: $text]").trim())
                    }
                    val r = SupabaseHub.updateRow(
                        dev.nixi.db.Tables.CALENDAR,
                        mapOf("id" to "eq.${e.getInt("id")}"), row
                    )
                    if (r.ok) changed++
                }
                if (changed > 0) {
                    ActionNotifier.notify(
                        app, "NIXI: cicha akcja",
                        "Zastępstwo („$subject”) — zaktualizowałam kalendarz ($changed).",
                        short = true
                    )
                    LogBus.log("rule.calendar_substitution", "$pkg → $subject ($changed)")
                }
            }

            else -> LogBus.log("rule.unknown", "typ: $type", "warn")
        }
    }

    suspend fun refreshRules() {
        rules.clear()
        try {
            val list = SupabaseHub.loadRules()
            rules.addAll(list)
            LogBus.log("rules.load", "${list.size} reguł")
        } catch (t: Throwable) {
            LogBus.log("rules.load", t.message ?: "?", "warn")
        }
    }
}
