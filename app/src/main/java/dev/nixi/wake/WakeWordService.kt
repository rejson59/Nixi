package dev.nixi.wake

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.nixi.NixiApp
import dev.nixi.NixiState
import dev.nixi.audio.AudioBus
import dev.nixi.live.LiveSessionService
import dev.nixi.notif.ActionNotifier
import dev.nixi.overlay.ConversationActivity
import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import kotlinx.coroutines.launch

/**
 * Nasłuch "Hej Nixi" — foreground service z typem microphone.
 * Działa w tle, nad blokadką, po restarcie (BootReceiver) i po zabiciu
 * przez system (STOP_STICKY).
 *
 * Oszczędność baterii:
 *  - pojedynczy AudioRecord (16 kHz) wspólny z sesją,
 *  - DTW tylko przy mowie (bramka),
 *  - tryb ECO (hop 20 ms, 8 pasm) w ustawieniach,
 *  - brak wakelocka — wątek śpi w read().
 */
class WakeWordService : Service() {

    companion object {
        const val NOTIF_ID = 1001
        @Volatile var running = false
            private set

        @Volatile var instance: WakeWordService? = null
            private set

        /** Wspólny silnik (konsument AudioBus). */
        val engine = WakeEngine()

        private val wakeConsumer = object : AudioBus.Consumer {
            override fun onPcm(pcm: ShortArray, len: Int) {
                if (!LocalStore.wakeEnabled) return
                if (NixiState.inSession.value) return
                val svc = instance ?: return
                if (engine.onPcm(pcm, len)) svc.onWakeHit()
            }
        }

        fun start(context: Context) {
            if (context.checkSelfPermission("android.permission.RECORD_AUDIO")
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActionNotifier.notify(
                    context, "Brak uprawnień do mikrofonu",
                    "NIXI nie może nasłuchiwać „Hej Nixi”. Uznij uprawnienie w ustawieniach aplikacji.",
                    short = true
                )
                LogBus.log("wake.start", "brak uprawnienia mikrofonu", "warn")
                return
            }
            val mode = if (LocalStore.ecoMode) WakeEngine.Mode.ECO else WakeEngine.Mode.STANDARD
            engine.configure(mode, LocalStore.wakeSensitivity)
            if (LocalStore.wakeTemplates.isNotBlank()) {
                engine.loadFromJson(LocalStore.wakeTemplates)
            }
            if (!AudioBus.isRunning()) {
                AudioBus.start(wakeConsumer)
            } else {
                AudioBus.setConsumer(wakeConsumer)
            }
            val intent = Intent(context, WakeWordService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** Po zakończeniu sesji wracamy do nasłuchu. */
        fun restoreWakeConsumer() {
            if (AudioBus.isRunning()) AudioBus.setConsumer(wakeConsumer)
        }
    }

    private fun onWakeHit() {
        if (NixiState.inSession.value) return
        NixiState.wakeHitAt = System.currentTimeMillis()
        LogBus.log("wake.hit", "wykryto „Hej Nixi”")
        NixiApp.scope.launch {
            withIO {
                // 1) pauza multimediów — zanim cokolwiek innego ruszy
                dev.nixi.audio.MediaPauseController.pauseAll()
            }
            // 2) sesja głosowa (WebSocket Gemini Live)
            LiveSessionService.start(this@WakeWordService, "wake")
            // 3) okno z kulą — nad wszystkim, także nad blokadką
            val intent = Intent(this@WakeWordService, ConversationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            ActionNotifier.notify(
                this@WakeWordService, "NIXI aktywowana",
                "Multimedia wstrzymane. Mów, w czym mogę pomóc.",
                short = true
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        NixiState.wakeActive.value = true
        running = true
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LocalStore.wakeEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notif = ActionNotifier.fgsNotification(
            "NIXI nasłuchuje", "„Hej Nixi” — offline, niskie zużycie", NOTIF_ID
        )
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        instance = null
        NixiState.wakeActive.value = false
        // bez sesji wyłączamy wspólny rekord (oszczędność)
        if (!NixiState.inSession.value && !LiveSessionService.running) {
            AudioBus.stop()
        }
        LogBus.log("wake.stop", "usługa nasłuchu zatrzymana")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inline fun withIO(crossinline block: () -> Unit) {
        // pauseAll jest szybkie (best-effort); robimy to poza pętlą UI
        block()
    }
}
