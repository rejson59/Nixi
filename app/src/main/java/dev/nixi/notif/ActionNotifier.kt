package dev.nixi.notif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.nixi.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wszystkie ukryte działania NIXI (zapis pamięci, ciche reguły, edycje w
 * kalendarzu, akcje narzędzi w tle) są tu raportowane krótkim powiadomieniem.
 */
object ActionNotifier {

    const val CH_ACTIONS = "nixi_actions"
    const val CH_SESSION = "nixi_session"
    const val CH_REMINDER = "nixi_reminder"
    const val CH_ERROR = "nixi_errors"
    const val CH_WAKE = "nixi_wake"

    private lateinit var nm: NotificationManager
    private val channelsReady = AtomicBoolean(false)
    private var nextId = 3000

    fun init(context: Context) {
        nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    fun ensureChannels() {
        if (channelsReady.compareAndSet(false, true)) {
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_ACTIONS, "Akcje NIXI", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply {
                            description = "Krótkie raporty tego, co NIXI zrobiła za Tobą"
                            setShowBadge(false)
                        }
                )
                nm.createNotificationChannel(
                    NotificationChannel(CH_SESSION, "Sesja NIXI", NotificationManager.IMPORTANCE_LOW)
                        .apply {
                            description = "Status rozmowy"
                            setShowBadge(false)
                        }
                )
                nm.createNotificationChannel(
                    NotificationChannel(CH_REMINDER, "Przypomnienia", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "Przypomnienia i alarmy NIXI" }
                )
                nm.createNotificationChannel(
                    NotificationChannel(CH_ERROR, "Błędy NIXI", NotificationManager.IMPORTANCE_LOW)
                        .apply {
                            description = "Dla celów ulepszania aplikacji"
                            setShowBadge(false)
                        }
                )
                nm.createNotificationChannel(
                    NotificationChannel(CH_WAKE, "Wezwanie NIXI", NotificationManager.IMPORTANCE_HIGH)
                        .apply {
                            description = "Pełnoekranowe okno rozmowy po „Hej Nixi”"
                            setShowBadge(false)
                        }
                )
            }
        }
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Krótkie powiadomienie o akcji. [short] => niższa priorytetowość wizualna
     * (bez dźwięku, bez badge'a).
     */
    fun notify(context: Context, title: String, text: String, short: Boolean = false) {
        ensureChannels()
        if (context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // przed Androidem 13 uprawnienie jest zawsze
            if (Build.VERSION.SDK_INT >= 33) return
        }
        val builder = Notification.Builder(context, CH_ACTIONS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .setOngoing(false)
            .setCategory(if (short) Notification.CATEGORY_STATUS else Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        runCatching { nm.notify(nextId++, builder.build()) }
    }

    /** Powiadomienie ciągłe foreground service. */
    fun fgsNotification(title: String, text: String, id: Int): Notification {
        ensureChannels()
        val context = dev.nixi.NixiApp.ctx()
        return Notification.Builder(context, CH_SESSION)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent(context))
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Pełnoekranowe wezwanie okna rozmowy — legalny sposób pokazania aktywności
     * z tła (nawet nad ekranem blokady). Bez uprawnienia FSI zostaje heads-up.
     */
    fun wakeFullscreen(context: Context) {
        ensureChannels()
        val intent = Intent(context, dev.nixi.overlay.ConversationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 77, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(context, CH_WAKE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("NIXI słucha")
            .setContentText("Dotknij, aby otworzyć rozmowę")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        runCatching { nm.notify(7001, n) }
    }

    /** Powiadomienie z akcją użytkownika (np. wznowienie nasłuchu z tła). */
    fun urgentAction(
        context: Context,
        title: String,
        text: String,
        action: PendingIntent,
        id: Int,
        channel: String = CH_ACTIONS,
    ) {
        ensureChannels()
        val n = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(action)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        runCatching { nm.notify(id, n) }
    }

    /** Czy system pozwoli na pełnoekranowe wezwanie (Android 14+). */
    fun canUseFullScreenIntent(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true
    } catch (_: Throwable) {
        false
    }

    /** Powiadomienie przypomnienia (wysoka ważność, dźwięk). */
    fun reminder(context: Context, id: Int, title: String, text: String) {
        ensureChannels()
        val builder = Notification.Builder(context, CH_REMINDER)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(contentIntent(context))
        runCatching { nm.notify(id, builder.build()) }
    }
}
