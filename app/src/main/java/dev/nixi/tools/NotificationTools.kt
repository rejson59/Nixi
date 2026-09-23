package dev.nixi.tools

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
            val active = listener.activeNotifications.orEmpty()
            val fmt = SimpleDateFormat("HH:mm", Locale("pl"))
            val filtered = active.asReversed()
                .filter { appFilter.isBlank() || it.packageName.lowercase().contains(appFilter.lowercase()) }
                .take(limit.coerceIn(1, 30))
            if (filtered.isEmpty()) {
                ToolResult.ok("Brak nowych powiadomień${if (appFilter.isNotBlank()) " z $appFilter" else ""}.")
            } else {
                ToolResult.ok(
                    "Powiadomienia (${filtered.size}):\n" +
                        filtered.joinToString("\n") {
                            val extras = it.notification.extras
                            val t = extras.getCharSequence("android.title")?.toString().orEmpty()
                            val b = extras.getCharSequence("android.text")?.toString().orEmpty()
                            "- [${it.packageName}] ${fmt.format(Date(it.postTime))}: $t — $b"
                        }
                )
            }
        } catch (t: Throwable) {
            ToolResult.fail("Nie mogłam odczytać powiadomień: ${t.message}")
        }
    }
}
