package dev.nixi.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.util.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Dokładny alarm przypomnienia (AlarmManager). */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TITLE = "reminder_title"
        const val EXTRA_WHEN = "reminder_when"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Przypomnienie"
        val whenTxt = intent.getStringExtra(EXTRA_WHEN).orEmpty()
        // ID powiadomienia NIE może kolidować z powiadomieniami usług (1001-1003),
        // bo jedno kasowałoby drugie.
        val notifId = (5000 + (id % 100_000)).toInt()
        try {
            val body = if (whenTxt.isBlank()) title else "$title · $whenTxt"
            ActionNotifier.reminder(context, notifId, "NIXI · $title", body)
        } catch (t: Throwable) {
            LogBus.log("reminder.fire", "nie mogę pokazać powiadomienia: ${t.message}", "warn")
        }
        LogBus.log("reminder.fire", "#$id $title")
        if (id <= 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SupabaseHub.updateRow(
                    Tables.REMINDERS, mapOf("id" to "eq.$id"), JSONObject().put("done", true)
                )
            } catch (_: Throwable) {
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
