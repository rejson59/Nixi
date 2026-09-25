package dev.nixi.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.nixi.NixiApp
import dev.nixi.db.SupabaseHub
import dev.nixi.util.LogBus
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Ciche reguły na przychodzący SMS (np. eduVulcan / dziennik). */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val from = msgs.firstOrNull()?.originatingAddress.orEmpty()
        val body = msgs.joinToString(" ") { it.displayMessageBody.orEmpty() }
        if (body.isBlank()) return
        val pending = goAsync()
        NixiApp.scope.launch {
            try {
                val rules = runCatching { SupabaseHub.loadRules() }.getOrDefault(emptyList())
                for (rule in rules) {
                    if (!rule.optBoolean("active", true)) continue
                    val contains = rule.optString("contains").lowercase()
                    if (contains.isNotBlank() && !body.lowercase().contains(contains)) continue
                    val pkg = rule.optString("app_package")
                    if (pkg.isNotBlank() && !pkg.contains("vulcan") && pkg != "sms") continue
                    runCatching { SilentRules.apply(rule, "sms:$from", from, body) }
                        .onFailure { LogBus.log("sms.rule", it.message ?: "?", "error") }
                }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
