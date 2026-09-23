package dev.nixi.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.nixi.notif.ReminderScheduler
import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import dev.nixi.wake.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Po restarcie telefonu (i po aktualizacji aplikacji) wznawia nasłuch
 * „Hej Nixi” oraz odtwarza alarmy przypomnień (AlarmManager czyści je
 * przy restarcie systemu).
 */
class BootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext
        if (!LocalStore.onboarded) return

        runCatching {
            if (LocalStore.wakeEnabled) {
                LogBus.log("boot", "wznowienie nasłuchu ($action)")
                WakeWordService.start(app)
            }
        }.onFailure { LogBus.log("boot", "nasłuch: ${it.message}", "warn") }

        // AlarmManager nie przeżywa restartu — odtwórz przypomnienia z Supabase.
        val pending = goAsync()
        scope.launch {
            try {
                runCatching { ReminderScheduler.rescheduleAll(app, force = true) }
                    .onFailure { LogBus.log("boot.reminders", it.message ?: "?", "warn") }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
