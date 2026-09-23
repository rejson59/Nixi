package dev.nixi.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import dev.nixi.wake.WakeWordService

/** Wznowienie nasłuchu "Hej Nixi" po restarcie telefonu. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (LocalStore.onboarded && LocalStore.wakeEnabled) {
            LogBus.log("boot", "wznowienie nasłuchu")
            WakeWordService.start(context.applicationContext)
        }
    }
}
