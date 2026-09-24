package dev.nixi.wake

import android.app.PendingIntent
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
import dev.nixi.ui.MainActivity
import dev.nixi.util.LogBus
import kotlinx.coroutines.launch

/**
 * Nasłuch "Hej Nixi" — foreground service z typem microphone.
 * Działa w tle, nad blokadką, po restarcie (BootReceiver) i po zabiciu
 * przez system (START_STICKY → [ensureListening] uzbraja mikrofon ponownie).
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

        fun hasMicPermission(context: Context): Boolean =
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            val app = context.applicationContext
            if (!hasMicPermission(app)) {
                ActionNotifier.notify(
                    app, "Brak uprawnień do mikrofonu",
                    "NIXI nie może nasłuchiwać „Hej Nixi”. Udziel uprawnienia w ustawieniach aplikacji.",
                    short = true
                )
                LogBus.log("wake.start", "brak uprawnienia mikrofonu", "warn")
                return
            }
            ensureEngineConfigured()
            ensureCapture()
            try {
                app.startForegroundService(Intent(app, WakeWordService::class.java))
            } catch (t: Throwable) {
                // Android 12+: zakaz startu FGS z tła (np. zaraz po restarcie telefonu,
                // w tle przez HyperOS). Pokazujemy powiadomienie z akcją, żeby użytkownik
                // mógł wznowić nasłuch jednym tapnięciem.
                LogBus.log("wake.start", "system odmówił startu usługi: ${t.message}", "warn")
                wakeResumeNotification(app)
                instance?.stopSelf()
            }
        }

        fun ensureEngineConfigured() {
            val mode = if (LocalStore.ecoMode) WakeEngine.Mode.ECO else WakeEngine.Mode.STANDARD
            engine.configure(mode, LocalStore.wakeSensitivity)
            if (LocalStore.wakeTemplates.isNotBlank()) {
                if (!engine.hasTemplates()) engine.loadFromJson(LocalStore.wakeTemplates)
            }
        }

        /** Utrzymuje JEDEN wspólny mikrofon uzbrojony na nasłuch. */
        fun ensureCapture() {
            if (!AudioBus.isRunning()) {
                AudioBus.start(wakeConsumer)
            } else {
                AudioBus.setConsumer(wakeConsumer)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** Po zakończeniu sesji (albo po rejestracji szablonu) wracamy do nasłuchu. */
        fun restoreWakeConsumer() {
            if (!LocalStore.wakeEnabled) return
            ensureEngineConfigured()
            ensureCapture()
        }

        /** Powiadomienie ratunkowe: system nie pozwolił wystartować usługi w tle. */
        private fun wakeResumeNotification(context: Context) {
            val pi = PendingIntent.getActivity(
                context, 91,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("resume_wake", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            ActionNotifier.urgentAction(
                context,
                "NIXI: nasłuch wstrzymany",
                "System nie pozwolił włączyć nasłuchu „Hej Nixi” w tle. Dotknij, aby wznowić.",
                pi,
                id = 7010,
                channel = ActionNotifier.CH_WAKE
            )
        }
    }

    private fun onWakeHit() {
        if (NixiState.inSession.value || LiveSessionService.running) return
        NixiState.wakeHitAt = System.currentTimeMillis()
        NixiState.wakeTrigger = "wake"
        LogBus.log("wake.hit", "wykryto „Hej Nixi”")
        NixiApp.scope.launch {
            // 1) pauza multimediów — zanim cokolwiek innego ruszy
            runCatching { dev.nixi.audio.MediaPauseController.pauseAll() }
            dev.nixi.NixiState.emit(dev.nixi.NixiState.NixiEvent.SessionStarted("wake"))
            // 2) okno z kulą — nad wszystkim, także nad blokadką
            val intent = Intent(this@WakeWordService, ConversationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(intent) }
            // Android 10+ blokuje startActivity z tła; fullScreenIntent na powiadomieniu
            // to legalna ścieżka (a bez FSI zostaje heads-up).
            ActionNotifier.wakeFullscreen(this@WakeWordService)
            // 3) sesja głosowa (WebSocket Gemini Live)
            LiveSessionService.start(this@WakeWordService, "wake")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        NixiState.wakeActive.value = true
        running = true
        startForegroundCompat()
        // nasłuch żyje => piesek pilnuje, żeby tak zostało
        runCatching { dev.nixi.boot.WakeWatchdog.arm(this) }
    }

    /**
     * Użytkownik zrzucił aplikację z listy ostatnich. Na HyperOS kończy się to
     * często ubiciem procesu — alarm za minutę spróbuje podnieść nasłuch.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        LogBus.log("wake.task", "aplikacja zrzucona z listy — planuję powrót nasłuchu", "warn")
        runCatching { dev.nixi.boot.WakeWatchdog.arm(this, dev.nixi.boot.WakeWatchdog.RETRY_MS) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LocalStore.wakeEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasMicPermission(this)) {
            LogBus.log("wake.onstart", "brak uprawnienia mikrofonu — zatrzymuję", "warn")
            stopSelf()
            return START_NOT_STICKY
        }
        // Po restarcie przez system (START_STICKY) proces startuje od zera:
        // AudioBus i szablony trzeba uzbroić ponownie.
        ensureEngineConfigured()
        ensureCapture()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        try {
            val notif = ActionNotifier.fgsNotification(
                "NIXI nasłuchuje", "„Hej Nixi” — offline, niskie zużycie", NOTIF_ID
            )
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            // np. mikrofon zabroniony w tle (Android 14+, start z BOOT_COMPLETED)
            LogBus.log("wake.fgs", "startForeground nieudany: ${t.message}", "warn")
            wakeResumeNotification(this)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        instance = null
        NixiState.wakeActive.value = false
        // bez sesji wyłączamy wspólny rekord (oszczędność baterii i prywatność)
        if (!NixiState.inSession.value && !LiveSessionService.running) {
            AudioBus.stop()
        }
        LogBus.log("wake.stop", "usługa nasłuchu zatrzymana")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
