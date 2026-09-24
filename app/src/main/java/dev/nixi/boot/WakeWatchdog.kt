package dev.nixi.boot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.nixi.NixiApp
import dev.nixi.db.OfflineQueue
import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import dev.nixi.wake.WakeWordService
import kotlinx.coroutines.launch

/**
 * Piesek pilnujący nasłuchu „Hej Nixi”.
 *
 * Po co: na HyperOS/MIUI foreground service potrafi zostać ubity, gdy aplikacja
 * jest zamknięta (albo po zrzuceniu z listy ostatnich aplikacji). `START_STICKY`
 * nie zawsze wystarcza, więc raz na kwadrans sprawdzamy, czy nasłuch żyje i —
 * jeśli nie — uzbrajamy go ponownie. Alarmy są też okazją, żeby doręczyć
 * zaległe zapisy z kolejki offline.
 *
 * Uczciwe ograniczenia:
 *  - nie działa po „Wymuś zatrzymanie” (system kasuje wtedy alarmy aplikacji),
 *  - gdy użytkownik wyłączy nasłuch w Ustawieniach, alarm jest odwoływany.
 */
object WakeWatchdog {

    const val ACTION = "dev.nixi.WATCHDOG"

    /** Co ile sprawdzamy stan nasłuchu. */
    private const val INTERVAL_MS = 15 * 60 * 1000L

    /** Krótka zwłoka — np. zaraz po zrzuceniu aplikacji z listy ostatnich. */
    const val RETRY_MS = 60 * 1000L

    private const val REQUEST_CODE = 7331

    fun arm(context: Context, delayMs: Long = INTERVAL_MS) {
        val app = context.applicationContext
        if (!LocalStore.onboarded || !LocalStore.wakeEnabled) {
            cancel(app)
            return
        }
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = System.currentTimeMillis() + delayMs
        val pi = pendingIntent(app)
        val canExact = Build.VERSION.SDK_INT < 31 ||
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (t: Throwable) {
            // np. brak uprawnienia do dokładnych alarmów — wystarczy niedokładny
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                .onFailure { LogBus.log("watchdog", "nie mogę uzbroić alarmu: ${it.message}", "warn") }
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(app)) }
    }

    /** Sprawdzenie stanu nasłuchu + doręczenie zaległych zapisów. */
    fun check(context: Context) {
        val app = context.applicationContext
        if (!LocalStore.onboarded || !LocalStore.wakeEnabled) return
        if (!WakeWordService.running && WakeWordService.hasMicPermission(app)) {
            LogBus.log("watchdog", "nasłuch nie działa — wznawiam", "warn")
            WakeWordService.start(app)
        } else if (WakeWordService.running) {
            // przy okazji: przy słabej baterii zejdź w tryb ECO (oszczędność)
            runCatching { WakeWordService.ensureEngineConfigured() }
        }
        // przy okazji: dociągnij to, co nie doszło do Supabase
        NixiApp.scope.launch {
            runCatching { OfflineQueue.flush(app) }
                .onFailure { LogBus.log("queue.flush", it.message ?: "?", "warn") }
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val i = Intent(context, WatchdogReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
