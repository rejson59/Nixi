package dev.nixi.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Odbiera alarm pieska i od razu uzbraja kolejny cykl. */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WakeWatchdog.ACTION) return
        val app = context.applicationContext
        WakeWatchdog.check(app)
        WakeWatchdog.arm(app)
    }
}
