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

    private var rulesJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        NixiApp.scope.launch { refreshRules() }
        // Reguły ciche żyją w Supabase — odświeżamy je także w tle, bo usługa
        // bywa jedynym „żywym" komponentem NIXI przez wiele godzin.
        rulesJob = NixiApp.scope.launch {
            while (true) {
                kotlinx.coroutines.delay(10 * 60 * 1000L)
                runCatching { refreshRules() }
            }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        rulesJob?.cancel()
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
                runCatching { SilentRules.apply(rule, sbn.packageName, title, text) }
                    .onFailure { LogBus.log("rule.err", it.message ?: "?", "error") }
            }
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
