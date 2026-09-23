package dev.nixi.tools

import android.service.notification.StatusBarNotification
import dev.nixi.notif.NixiNotificationListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Odczyt powiadomień telefonu (wymaga "Dostęp do powiadomień"). */
object NotificationTools {

    fun read(appFilter: String, limit: Int): ToolResult {
        val listener = NixiNotificationListener.instance
        if (listener == null) {
            return ToolResult.fail(
                "Brak dostępu do powiadomień. Ustawienia → Powiadomienia → Dostęp do powiadomień → NIXI."
            )
        }
        return try {
            val raw: Array<StatusBarNotification>? = listener.getActiveNotifications()
            val active = raw ?: emptyArray()
            val fmt = SimpleDateFormat("HH:mm", Locale("pl"))
            val filtered = active.toList().asReversed()
                .filter { s -> appFilter.isBlank() || s.packageName.lowercase().contains(appFilter.lowercase()) }
                .take(limit.coerceIn(1, 30))
            if (filtered.isEmpty()) {
                ToolResult.ok("Brak nowych powiadomień${if (appFilter.isNotBlank()) " z $appFilter" else ""}.")
            } else {
                ToolResult.ok(
                    "Powiadomienia (${filtered.size}):\n" +
                        filtered.map { n ->
                            val extras = n.notification.extras
                            val t = extras?.getCharSequence("android.title")?.toString().orEmpty()
                            val b = extras?.getCharSequence("android.text")?.toString().orEmpty()
                            "- [${n.packageName}] ${fmt.format(Date(n.postTime))}: $t — $b"
                        }.joinToString("\n")
                )
            }
        } catch (t: Throwable) {
            ToolResult.fail("Nie mogłam odczytać powiadomień: ${t.message}")
        }
    }
}
