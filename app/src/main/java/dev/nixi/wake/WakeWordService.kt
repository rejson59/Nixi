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
import dev.nixi.store.LocalStore
import dev.nixi.ui.MainActivity
import android.os.BatteryManager
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
                // Ile realnego CPU zjada nasłuch — mierzone, bo w Ustawieniach
                // pokazujemy tę liczbę użytkownikowi (wcześniej zawsze 0).
                val t0 = System.nanoTime()
                val hit = engine.onPcm(pcm, len)
                cpuNanos += System.nanoTime() - t0
                maybeLogStats()
                if (hit) svc.onWakeHit()
            }
        }

        /** Statystyki detektora raz na minutę — do dostrajania na telefonie. */
        @Volatile private var lastStatsLog = 0L

        @Volatile private var cpuNanos = 0L

        private fun maybeLogStats() {
            val now = System.currentTimeMillis()
            if (now - lastStatsLog < 60_000) return
            // Normalizujemy do „na minutę", bo pierwsze okno po restarcie
            // usługi może być krótsze niż minuta.
            val window = if (lastStatsLog == 0L) 60_000L else now - lastStatsLog
            val cpuMs = cpuNanos / 1_000_000
            val perMinute = if (window > 0) cpuMs * 60_000 / window else cpuMs
            LocalStore.wakeCpuMsPerMin = perMinute
            cpuNanos = 0
            lastStatsLog = now
            LogBus.log(
                "wake.stats",
                engine.statsSummary() + " " + engine.diagnostics() +
                    " cpu=${cpuMs}ms/${window}ms (~${perMinute}ms/min)"
            )
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

        /** Czy ostatnio wymusiliśmy ECO z powodu baterii (żeby nie spamować logów). */
        @Volatile private var autoEco = false

        /**
         * Poniżej 20% baterii (i bez ładowania) nasłuch przechodzi w tryb ECO:
         * mniej ciepła i zużycia, a „Hej Nixi" nadal działa.
         */
        private fun lowBattery(): Boolean = try {
            val ctx = NixiApp.ctx()
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            level in 1..20 && !bm.isCharging
        } catch (_: Throwable) {
            false
        }

        fun ensureEngineConfigured() {
            val eco = LocalStore.ecoMode || lowBattery()
            val mode = if (eco) WakeEngine.Mode.ECO else WakeEngine.Mode.STANDARD
            val auto = eco && !LocalStore.ecoMode
            if (auto != autoEco) {
                autoEco = auto
                if (auto) LogBus.log("wake.eco", "mało baterii — nasłuch w trybie ECO")
                else LogBus.log("wake.eco", "wracam do trybu STANDARD")
            }
            engine.configure(mode, LocalStore.wakeSensitivity)
            // Szablon ładujemy, gdy się zmienił (albo po starcie procesu).
            // Wcześniej warunkiem było „brak szablonów", więc po nieudanym
            // wczytaniu próbowaliśmy w kółko i log zapełniał się ostrzeżeniami.
            val json = LocalStore.wakeTemplates
            if (json.isNotBlank() && json != loadedTemplatesJson) {
                loadedTemplatesJson = json
                engine.loadFromJson(json)
                LocalStore.wakeNeedsEnroll = engine.requiresReenroll
            }
        }

        @Volatile private var loadedTemplatesJson = ""

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
            runCatching { dev.nixi.overlay.ConversationHost.show(this@WakeWordService) }
            val locked = (getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager)
                ?.isKeyguardLocked == true
            // Overlay nie potrzebuje aktywności. FSI tylko nad blokadką albo gdy
            // system nie pozwala rysować nad innymi aplikacjami.
            if (locked || !dev.nixi.overlay.ConversationHud.canShow(this@WakeWordService)) {
                ActionNotifier.wakeFullscreen(this@WakeWordService)
            }
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
