package dev.nixi.live

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.nixi.NixiApp
import dev.nixi.NixiState
import dev.nixi.audio.AudioBus
import dev.nixi.audio.AudioPlayer
import dev.nixi.audio.MediaPauseController
import dev.nixi.notif.ActionNotifier
import dev.nixi.screen.ScreenCaptureService
import dev.nixi.store.LocalStore
import dev.nixi.tools.ToolContext
import dev.nixi.tools.ToolResult
import dev.nixi.util.LogBus
import dev.nixi.wake.WakeWordService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sesja głosowa z Gemini Live.
 *  - WebSocket (BidiGenerateContent) + PCM 16 kHz w górę, 24 kHz w dół,
 *  - narzędzia (ToolRegistry) z bramką potwierdzeń dla usuwań,
 *  - TpmGuard: klient NIGDY nie przekroczy limitu TPM darmowego planu,
 *  - pauza multimediów na start, wznowienie na koniec,
 *  - po sesji: podsumowanie + fakty do pamięci długotrwałej (REST).
 *
 * Cykl życia: usługa startuje z [start], pierwszy `onStartCommand` uruchamia
 * sesję (foreground z typem microphone), a `endSession` sprząta WSZYSTKO:
 * mikrofon, odtwarzacz, projekcję ekranu, oczekujące potwierdzenia i media.
 */
class LiveSessionService : Service() {

    companion object {
        const val NOTIF_ID = 1002
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_REASON = "reason"
        const val ACTION_STOP = "dev.nixi.live.STOP"

        /** Ile razy wznawiamy połączenie, zanim zakończymy sesję. */
        private const val MAX_RECONNECTS = 3

        /** Poziom mikrofonu, od którego uznajemy, że użytkownik przerwał NIXI. */
        private const val BARGE_LEVEL = 0.45f

        /** Ile klatek (64 ms) pod rząd musi być głośno, żeby przerwać. */
        private const val BARGE_FRAMES = 2

        @Volatile var running = false
            private set

        /** Żeby dwa startForegroundService nie zrobiły dwóch sesji / dwóch pigułek. */
        private val startGate = java.util.concurrent.atomic.AtomicBoolean(false)

        @Volatile var instance: LiveSessionService? = null
            private set

        /** Oczekujące potwierdzenia (id -> decyzja) — wspólne dla UI i usługi. */
        private val decisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

        fun start(context: Context, trigger: String) {
            if (running || startGate.get()) {
                LogBus.log("live.start", "sesja już trwa — ignoruję start ($trigger)")
                return
            }
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActionNotifier.notify(
                    context, "NIXI: mikrofon",
                    "Brak uprawnienia mikrofonu — sesja głosowa nie może ruszyć.",
                    short = false
                )
                return
            }
            if (!startGate.compareAndSet(false, true)) return
            val intent = Intent(context, LiveSessionService::class.java)
                .putExtra(EXTRA_TRIGGER, trigger)
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                startGate.set(false)
                LogBus.log("live.start", "nie mogę wystartować usługi: ${t.message}", "error")
                // Z tła Android 14 często blokuje FGS mikrofonu — otwórz okno
                // (to jest już foreground) i spróbuj stamtąd.
                runCatching {
                    context.startActivity(
                        Intent(context, dev.nixi.overlay.ConversationActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("start_session", true)
                            .putExtra(EXTRA_TRIGGER, trigger)
                    )
                }
            }
        }

        internal fun releaseStartGate() {
            startGate.set(false)
        }

        /** Grzeczne zakończenie sesji (np. przycisk „koniec” w oknie rozmowy). */
        fun stop(context: Context, reason: String = "użytkownik zakończył") {
            if (!running) return
            val intent = Intent(context, LiveSessionService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_REASON, reason)
            runCatching { context.startService(intent) }
                .onFailure { runCatching { context.stopService(Intent(context, LiveSessionService::class.java)) } }
        }

        /** Odpowiedź na okno potwierdzenia (UI okna rozmowy). */
        fun confirm(pendingId: String, approved: Boolean) {
            decisions[pendingId]?.complete(approved)
        }

        /** Awaryjne domknięcie wszystkich czekających potwierdzeń (koniec sesji). */
        internal fun cancelAllDecisions() {
            decisions.values.forEach { runCatching { it.complete(false) } }
            decisions.clear()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var client: GeminiLiveClient? = null
    private val player = AudioPlayer()
    private val tpm = TpmGuard()
    private val sessionStarted = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private var sessionStart = 0L
    private val transcripts = mutableListOf<Pair<String, String>>()
    private val textBuffer = StringBuilder()
    private var idleJob: Job? = null
    private var tpmJob: Job? = null

    @Volatile private var lastUserActivity = 0L
    @Volatile private var throttled = false
    @Volatile private var trigger = "button"
    @Volatile private var promptText = ""
    @Volatile private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    /**
     * Wariant wiadomości `setup` (patrz [buildSetup]) i licznik prób jego
     * dopasowania. `-1` = jeszcze nie wiemy, który działa — bierzemy domyślny.
     */
    @Volatile private var setupVariant = -1
    private var setupTries = 0

    /** True po `setupComplete` — czyli sesja naprawdę rozmawia z modelem. */
    @Volatile private var setupDone = false

    /** Ile razy model przysłał dźwięk (dowód, że rozmowa realnie działa). */
    @Volatile private var audioReplies = 0

    override fun onCreate() {
        super.onCreate()
        ToolContext.app = NixiApp.app
        ToolContext.screenWidthPx = resources.displayMetrics.widthPixels
        ToolContext.screenHeightPx = resources.displayMetrics.heightPixels
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            endSession(intent.getStringExtra(EXTRA_REASON) ?: "użytkownik zakończył")
            return START_NOT_STICKY
        }
        if (intent == null) {
            // restart przez system (my nigdy nie chcemy START_STICKY dla sesji)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!sessionStarted.compareAndSet(false, true)) return START_NOT_STICKY
        trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "button"
        NixiState.wakeTrigger = trigger
        running = true
        if (!startForegroundCompat("NIXI rozmawia", "Dotknij, aby otworzyć okno rozmowy")) {
            running = false
            sessionStarted.set(false)
            startGate.set(false)
            ActionNotifier.notify(
                this, "NIXI",
                "System nie pozwolił na mikrofon w tle. Otwieram okno rozmowy — spróbuj z niego.",
                short = true
            )
            runCatching {
                startActivity(
                    Intent(this, dev.nixi.overlay.ConversationActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("start_session", true)
                        .putExtra(EXTRA_TRIGGER, trigger)
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }
        beginSession()
        return START_NOT_STICKY
    }

    /** Użytkownik wyraził zgodę na podgląd ekranu (MediaProjection). */
    fun onScreenConsent() {
        if (client == null) return
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        NixiState.orbState.value = NixiState.OrbState.MANUAL
        sendTextCounted(
            "Użytkownik wyraził zgodę na podgląd ekranu (rozdzielczość ${w}x$h). " +
                "Tryb ręczny jest gotowy — użyj screen_get, aby zobaczyć ekran, i wykonaj zadanie. " +
                "Po wykonaniu zadania zakończ tryb przez screen_manual_stop."
        )
        lastUserActivity = System.currentTimeMillis()
        LogBus.log("manual.consent", "zgoda na podgląd ekranu")
    }

    /** Użytkownik odmówił zgody na podgląd ekranu. */
    fun onScreenDenied() {
        NixiState.manualMode.value = false
        NixiState.orbState.value = NixiState.OrbState.LISTENING
        sendTextCounted(
            "Użytkownik NIE wyraził zgody na podgląd ekranu. Nie próbuj ponownie w tej sesji — " +
                "powiedz krótko, że tryb ręczny wymaga zgody, i zaproponuj inne rozwiązanie."
        )
        LogBus.log("manual.denied", "brak zgody na podgląd ekranu", "warn")
    }

    /** Szybkie polecenie tekstowe od UI (np. „uruchom tryb ręczny”). */
    fun sendUserText(text: String) {
        if (text.isBlank()) return
        val c = client
        if (c == null || !c.isOpen()) {
            LogBus.log("live.text", "sesja niegotowa — pomijam tekst", "warn")
            return
        }
        lastUserActivity = System.currentTimeMillis()
        sendTextCounted(text)
    }

    private fun beginSession() {
        NixiState.inSession.value = true
        NixiState.sessionTrusted.value = LocalStore.trustDeletes
        NixiState.wantScreenCapture.value = false
        NixiState.lastToolLine.value = ""
        NixiState.lastHeard.value = ""
        NixiState.lastSaid.value = ""
        sessionStart = System.currentTimeMillis()
        lastUserActivity = sessionStart
        throttled = false
        tpm.reset()
        publishTpm()

        val key = LocalStore.geminiKey
        if (key.isBlank()) {
            ActionNotifier.notify(this, "NIXI: błąd", "Brak klucza API Gemini — wklej go w Ustawieniach.", short = false)
            endSession("brak klucza API")
            return
        }

        setupDone = false
        setupTries = 0
        setupVariant = -1
        audioReplies = 0
        NixiState.lastSessionError.value = ""

        player.start()
        // Usuwanie echa z głośnika — bez tego mikrofon zbiera głos NIXI
        // i wysyła go do modelu (fałszywe przerwania). Jeśli telefon nie
        // wspiera AEC, działa bramka półduplex w streamAudio().
        AudioBus.enableEchoCancel()
        val c = GeminiLiveClient(key, listener)
        client = c

        scope.launch {
            val manual = NixiState.manualMode.value
            val w = if (manual) resources.displayMetrics.widthPixels else 0
            val h = if (manual) resources.displayMetrics.heightPixels else 0
            val prompt = withTimeoutOrNull(1800) { SystemPromptBuilder.build(w, h) }
                ?: "Jesteś NIXI, osobistą asystentką. Mów po polsku, zwięźle."
            if (ended.get()) return@launch
            promptText = prompt
            connectSession()
            streamAudio()
            idleWatcher()
            tpmWatcher()
        }
    }

    /**
     * Sesja Live bez wznowienia żyje ~10 min i nie przeżywa zerwania sieci.
     * `sessionResumption` + uchwyt z poprzedniego połączenia pozwalają
     * kontynuować rozmowę (kontekst zostaje po stronie serwera).
     */
    private fun buildSetup(prompt: String, handle: String? = null, variant: Int = 0): JSONObject {
        // Ten sam model, instrukcja i narzędzia w każdym wariancie — różni się
        // tylko miejsce, w którym podajemy tryb odpowiedzi i głos. Dokumentacja
        // Google jest tu niespójna: przewodnik po WebSocketach pokazuje
        // `responseModalities` na najwyższym poziomie `setup`, a referencja API
        // trzyma je w `generationConfig` (i nie wymienia już `speechConfig`
        // na najwyższym poziomie). Zamiast zgadywać — próbujemy po kolei,
        // a działający wariant zapamiętujemy (LocalStore.liveSetupVariant).
        val setup = JSONObject()
            .put("model", "models/${LocalStore.geminiModel}")
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            )
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", ToolRegistry.declarations())))

        val voice = LocalStore.voiceName.ifBlank { "Puck" }
        val speech = JSONObject()
            .put(
                "voiceConfig",
                JSONObject().put(
                    "prebuiltVoiceConfig",
                    JSONObject().put("voiceName", voice)
                )
            )
        when (variant) {
            // 0 — wg referencji API: wszystko w generationConfig
            0 -> setup.put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", speech)
            )
            // 1 — wg przewodnika WebSocket: na najwyższym poziomie `setup`
            1 -> setup
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", speech)
            // 2 — minimalny: bez trybu odpowiedzi i bez głosu (serwer użyje
            //     domyślnych). Zostaje model, instrukcja i narzędzia.
            else -> Unit
        }
        // Uwaga: w speechConfig NIE ma pola audioConfig — Live API zawsze
        // zwraca PCM 24 kHz LINEAR16, a nieznane pole kończyło się błędem 400.
        if (variant <= 1) {
            // tekst rozmowy (user/nixi) do podsumowań i pamięci
            setup.put("inputAudioTranscription", JSONObject())
            setup.put("outputAudioTranscription", JSONObject())
        }
        setup.put(
            "sessionResumption",
            JSONObject().apply { if (!handle.isNullOrBlank()) put("handle", handle) }
        )
        setup.put(
            "realtimeInputConfig",
            JSONObject().put(
                "automaticActivityDetection",
                JSONObject()
                    .put("silenceDurationMs", 900)
                    .put("prefixPaddingMs", 300)
            )
        )
        return JSONObject().put("setup", setup)
    }

    /** Pierwsze połączenie i każde kolejne wznowienie idą tą samą drogą. */
    private fun connectSession() {
        val c = client ?: return
        val handle = c.resumptionHandle
        if (setupVariant < 0) setupVariant = LocalStore.liveSetupVariant
        if (setupDone) setupTries = 0
        val setup = buildSetup(promptText, handle, setupVariant)
        // Pierwsze gniazdo w tej sesji: connect(). Każde następne — również
        // po zmianie wariantu setupu — przez reconnect(), bo ono najpierw
        // zamyka stare gniazdo (inaczej zostałoby „zombie" wysyłające audio).
        if (c.connectCount == 0) c.connect(setup) else c.reconnect(setup)
        // Prompt systemowy też jest rozliczany przez serwer — liczymy go do
        // budżetu przy każdym połączeniu (wcześniej TPM widział tylko audio).
        tpm.addUsed(TpmGuard.tokensForText(promptText))
        publishTpm()
    }

    /** Wysyłka tekstu z doliczeniem do budżetu TPM. */
    private fun sendTextCounted(text: String) {
        val c = client ?: return
        c.sendText(text)
        tpm.addUsed(TpmGuard.tokensForText(text))
        publishTpm()
    }

    /**
     * Zaplanuj wznowienie po zerwaniu sieci / goAway. Ograniczone do
     * [MAX_RECONNECTS] prób z rosnącym odstępem; po ich wyczerpaniu kończymy
     * sesję z jasnym powodem (użytkownik nie zostaje z martwym mikrofonem).
     */
    /**
     * Przechodzi do kolejnego wariantu `setup`. Zwraca true, gdy przejęliśmy
     * obsługę błędu (nie wołaj wtedy scheduleReconnect/endSession).
     */
    private fun escalateSetup(why: String): Boolean {
        if (ended.get()) return false
        val tried = setupTries
        val next = setupVariant + 1
        if (tried >= 3 || next > 2) {
            NixiState.lastSessionError.value =
                "Nie udało się rozpocząć rozmowy z Gemini ($why). Sprawdź klucz API i model w Ustawieniach → Mózg."
            ActionNotifier.notify(
                this, "NIXI: nie mogę rozmawiać",
                "Gemini odrzuciło konfigurację sesji ($why). Sprawdź klucz API i nazwę modelu.",
                short = false
            )
            endSession("konfiguracja sesji odrzucona")
            return true
        }
        setupTries++
        setupVariant = next
        LogBus.log(
            "live.setup",
            "wariant $next nie zadziałał ($why) — próbuję kolejny ($setupTries/3)",
            "warn"
        )
        NixiState.orbState.value = NixiState.OrbState.THINKING
        scope.launch {
            delay(250)
            if (ended.get()) return@launch
            connectSession()
        }
        return true
    }

    /**
     * Ponowne połączenie po zerwaniu (sieć, goAway, restart procesu).
     * [MAX_RECONNECTS] prób z rosnącym odstępem; po ich wyczerpaniu kończymy
     * sesję z jasnym powodem (użytkownik nie zostaje z martwym mikrofonem).
     */
    private fun scheduleReconnect(why: String) {
        if (ended.get()) return
        if (reconnectJob?.isActive == true) return
        if (reconnectAttempts >= MAX_RECONNECTS) {
            LogBus.log("live.reconnect", "koniec prób ($why)", "error")
            endSession("brak połączenia z Gemini ($why)")
            return
        }
        reconnectAttempts++
        val waitMs = if (why == "goAway") 400L else 1500L * reconnectAttempts
        LogBus.log("live.reconnect", "próba $reconnectAttempts/$MAX_RECONNECTS za ${waitMs} ms ($why)", "warn")
        NixiState.orbState.value = NixiState.OrbState.THINKING
        NixiState.emit(NixiState.NixiEvent.ErrorHappened("live", "wznawiam połączenie ($why)"))
        reconnectJob = scope.launch {
            delay(waitMs)
            if (ended.get()) return@launch
            if (client?.isOpen() == true) return@launch
            connectSession()
        }
    }

    /** Strumień mikrofonu do Live API (z TpmGuard, AEC i barge-inem). */
    private fun streamAudio() {
        val consumer = object : AudioBus.Consumer {
            /** Licznik głośnych klatek pod rząd — chroni przed echem głośnika. */
            private var loudFrames = 0

            /** Czy logowaliśmy już pominięcie klatek (raz na sesję, bez spamu). */
            private var gateLogged = false

            override fun onPcm(pcm: ShortArray, len: Int) {
                val c = client ?: return
                if (!c.isOpen() || !setupDone) return
                // Bramka półduplex: gdy NIXI mówi, mikrofon zbiera jej własny
                // głos z głośnika. Wysyłanie tego do modelu kończyło się
                // „rozmową z samą sobą” i fałszywymi przerwaniami.
                //
                // Dlaczego nie polegamy na AEC: część telefonów zwraca obiekt
                // AEC, które „jest włączone", ale realnie nic nie tłumi.
                // Bramka jest deterministyczna. AEC zostaje włączone, bo
                // poprawia jakość klatek, które i tak wysyłamy (np. zaraz po
                // przerwaniu, gdy głos NIXI jeszcze wybrzmiewa).
                //
                // Barge-in nadal działa: poziom leci osobnym callbackiem,
                // a przerwanie czyści kolejkę odtwarzacza (patrz onLevel) —
                // po nim bramka otwiera się natychmiast.
                if (player.isPlaying()) {
                    if (!gateLogged) {
                        gateLogged = true
                        LogBus.log("live.gate", "NIXI mówi — nie wysyłam jej własnego głosu do modelu")
                    }
                    return
                }
                val sec = len / 16000.0
                val tokens = TpmGuard.tokensForAudioSeconds(sec)
                if (!tpm.canSend(tokens)) {
                    if (!throttled) {
                        throttled = true
                        NixiState.orbState.value = NixiState.OrbState.TPM_LIMIT
                        NixiState.emit(NixiState.NixiEvent.TpmLimited("Limit TPM — chwilowa pauza"))
                        LogBus.log("tpm.throttle", "pauza nadawania", "warn")
                        val wait = tpm.backoffSec().coerceAtLeast(15)
                        sendTextCounted(
                            "Limit tokenów na minutę. Powiedz użytkownikowi jednym zdaniem, " +
                                "że pauza trwa około ${wait}s i zaraz wrócisz."
                        )
                        ActionNotifier.notify(
                            this@LiveSessionService, "NIXI",
                            "Limit tokenów — pauza ok. ${wait}s.",
                            short = true
                        )
                    }
                    publishTpm()
                    return
                }
                if (throttled) {
                    throttled = false
                    if (NixiState.orbState.value == NixiState.OrbState.TPM_LIMIT) {
                        NixiState.orbState.value = NixiState.OrbState.LISTENING
                    }
                }
                tpm.addUsed(tokens)
                // PCM little-endian -> bajty -> base64
                val bytes = ByteArray(len * 2)
                for (i in 0 until len) {
                    bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
                }
                c.sendAudio(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }

            override fun onLevel(level: Float) {
                // Poziom NIE resetuje bezczynności — szum w pokoju trzymałby
                // sesję w nieskończoność. Liczy się transkrypt / narzędzie.
                val barge = if (headsetOut()) BARGE_LEVEL * 0.7f else BARGE_LEVEL
                loudFrames = if (level > barge) loudFrames + 1 else 0
                val speaking = NixiState.orbState.value == NixiState.OrbState.SPEAKING ||
                    player.isPlaying()
                if (speaking && loudFrames >= BARGE_FRAMES) {
                    loudFrames = 0
                    player.flush()
                    NixiState.orbState.value = NixiState.OrbState.LISTENING
                    LogBus.log("live.bargein", "przerwano odpowiedź (poziom %.2f)".format(level))
                }
            }
        }
        AudioBus.setConsumer(consumer)
        if (!AudioBus.isRunning()) AudioBus.start(consumer)
    }

    private fun idleWatcher() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (running && !ended.get()) {
                delay(5000)
                if (!AudioBus.isRunning()) {
                    LogBus.log("live.mic", "mikrofon padł w sesji — wznawiam", "warn")
                    runCatching { streamAudio() }
                }
                val last = maxOf(lastUserActivity, NixiState.sessionKeepAliveAt)
                val idle = System.currentTimeMillis() - last
                val inManual = NixiState.manualMode.value
                if (idle > 50_000 && !inManual) {
                    endSession("bezczynność (50 s)")
                    break
                }
                if (idle > 20_000 && !inManual && !manuallyReminded && NixiState.orbState.value != NixiState.OrbState.SPEAKING) {
                    manuallyReminded = true
                    sendTextCounted("Użytkownik milczy od 20 sekund. Zapytaj jednym zdaniem, czy kończyć, albo czekaj.")
                }
            }
        }
    }

    private fun handleLocalPhrase(raw: String): Boolean {
        val t = raw.lowercase().trim().trimEnd('.', '!', '?', ',', '…')
        if (t.length < 4) return false
        val later = t in setOf("później", "pozniej", "poczekaj", "czekaj", "jeszcze chwila", "nie kończ", "nie koncz")
        val end = t in setOf(
            "koniec", "do widzenia", "na razie", "cicho", "wyłącz się", "wylacz sie",
            "dziękuję to wszystko", "dziekuje to wszystko", "stop", "zamknij się", "zamknij sie",
        )
        if (later) {
            NixiState.sessionKeepAliveAt = System.currentTimeMillis() + 150_000L
            LogBus.log("live.phrase", "później — czekam dłużej")
            return true
        }
        if (end) {
            LogBus.log("live.phrase", "koniec: $t")
            endSession("komenda: $t")
            return true
        }
        return false
    }

    private fun headsetOut(): Boolean {
        return try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        } catch (_: Throwable) {
            false
        }
    }

    @Volatile private var manuallyReminded = false

    /** Podgląd zużycia TPM na kuli (i w logach co 15 s). */
    private fun tpmWatcher() {
        tpmJob?.cancel()
        tpmJob = scope.launch {
            while (running && !ended.get()) {
                publishTpm()
                delay(15_000)
            }
        }
    }

    private fun publishTpm() {
        val s = tpm.snapshot()
        NixiState.tpm.value = NixiState.TpmInfo(
            used = s.used,
            limit = s.limit,
            percent = s.percent,
            backoffSec = s.backoffSec,
        )
    }

    private val listener = object : GeminiLiveClient.Listener {
        override fun onSetupComplete() {
            reconnectAttempts = 0
            reconnectJob?.cancel()
            val first = !setupDone
            setupDone = true
            // Ten wariant zadziałał — zapamiętujemy go, żeby następna sesja
            // (i restart telefonu) nie powtarzała nieudanych prób.
            if (setupTries > 0 || LocalStore.liveSetupVariant != setupVariant) {
                LocalStore.liveSetupVariant = setupVariant
                LogBus.log("live.setup", "działający wariant setupu: $setupVariant (zapisany)")
            }
            setupTries = 0
            NixiState.lastSessionError.value = ""
            LogBus.log(
                "live.setup",
                "sesja gotowa (trigger=$trigger, połączenie #${client?.connectCount ?: 1}, wariant=$setupVariant)"
            )
            NixiState.orbState.value = NixiState.OrbState.LISTENING
            if (first && LocalStore.dingEnabled) playDing(dev.nixi.R.raw.ding_start)
            // Pierwsze słowo od NIXI — bez tego sesja bywała cicha, gdy VAD
            // nie złapał powitania użytkownika (cichy pokój, daleki mikrofon).
            if (first) {
                sendTextCounted(
                    "Użytkownik właśnie Cię wywołał. Przywitaj się krótko po polsku i czekaj na pytanie."
                )
            }
        }

        override fun onAudio(pcm: ByteArray) {
            audioReplies++
            player.write(pcm)
            // wyjście modelu też wlicza się do limitu TPM
            tpm.addUsed(TpmGuard.tokensForOutputSeconds(pcm.size / 2 / 24000.0))
            if (NixiState.orbState.value != NixiState.OrbState.SPEAKING &&
                NixiState.orbState.value != NixiState.OrbState.MANUAL
            ) {
                NixiState.orbState.value = NixiState.OrbState.SPEAKING
            }
        }

        override fun onTextDelta(text: String) {
            textBuffer.append(text)
            if (textBuffer.length > 4000) textBuffer.delete(0, textBuffer.length - 4000)
        }

        override fun onInputTranscript(text: String) {
            if (text.isNotBlank()) {
                transcripts.add("user" to text)
                NixiState.lastHeard.value = text.take(400)
                lastUserActivity = System.currentTimeMillis()
                if (handleLocalPhrase(text)) return
            }
        }

        override fun onOutputTranscript(text: String) {
            if (text.isNotBlank()) {
                transcripts.add("nixi" to text)
                NixiState.lastSaid.value = text.take(400)
            }
        }

        override fun onToolCall(callId: String, calls: List<JSONObject>) {
            NixiState.orbState.value = NixiState.OrbState.THINKING
            scope.launch { handleToolCalls(callId, calls) }
        }

        override fun onTurnComplete() {
            if (NixiState.orbState.value == NixiState.OrbState.SPEAKING ||
                NixiState.orbState.value == NixiState.OrbState.THINKING
            ) {
                NixiState.orbState.value = if (NixiState.manualMode.value) {
                    NixiState.OrbState.MANUAL
                } else {
                    NixiState.OrbState.LISTENING
                }
            }
        }

        override fun onGoAway() {
            val left = client?.goAwayMillis ?: 0
            LogBus.log("live.goaway", "serwer kończy sesję (timeLeft=${left} ms) — wznawiam", "warn")
            scheduleReconnect("goAway")
        }

        override fun onSessionEnd() {
            endSession("koniec sesji")
        }

        override fun onApiError(code: Int, message: String) {
            LogBus.log(
                "live.error",
                "code=$code wariant=${setupVariant} setup=${if (setupDone) "ok" else "nieudany"} $message",
                "error"
            )
            NixiState.lastSessionError.value = "$code: ${message.take(300)}"

            // Dopasowanie formatu `setup`: dopóki serwer nie potwierdził
            // setupComplete, każdy błąd traktujemy jak „ten wariant jest zły”
            // i próbujemy następny — zamiast zamykać sesję bez słowa.
            if (!setupDone && (code == 400 || code == 401 || code == 1007 || code == 1008)) {
                if (escalateSetup("błąd $code")) return
            }

            when {
                // limit tempa: nie zabijamy rozmowy — TpmGuard wstrzyma nadawanie
                code == 429 -> {
                    tpm.noteRateLimit()
                    publishTpm()
                    ActionNotifier.notify(
                        this@LiveSessionService, "NIXI: limit tokenów",
                        "Zbyt wiele tokenów na minutę (limit darmowego planu). Robię krótką przerwę.",
                        short = true
                    )
                    scheduleReconnect("429")
                }
                // zły klucz / model — ponawianie nic nie da
                code == 400 || code == 401 || code == 403 -> {
                    ActionNotifier.notify(
                        this@LiveSessionService, "NIXI: błąd API",
                        "Gemini Live: ${message.take(200)}. Sprawdź klucz/model w Ustawieniach.",
                        short = false
                    )
                    endSession("błąd API $code")
                }
                else -> scheduleReconnect("błąd $code")
            }
        }

        override fun onClosed(code: Int, reason: String) {
            if (ended.get()) return
            // nasze własne zamknięcie (koniec sesji) — nic nie rób
            if (client?.closedByUs == true) return
            // Serwer potrafi odrzucić zły `setup` zamknięciem gniazda bez
            // komunikatu `error` (np. 1007 „invalid frame payload”). Zwykłe
            // zerwanie sieci (1006) to nie wina formatu — tam ponawiamy.
            if (!setupDone && (code == 1007 || code == 1008)) {
                if (escalateSetup("zamknięto $code${if (reason.isBlank()) "" else " ($reason)"}")) return
            }
            scheduleReconnect("zamknięto $code")
        }
    }

    private suspend fun handleToolCalls(callId: String, calls: List<JSONObject>) {
        val responses = JSONArray()
        for (call in calls) {
            val name = call.optString("name", "")
            val args = call.optJSONObject("args") ?: JSONObject()
            val fcId = call.optString("id", UUID.randomUUID().toString())
            NixiState.emit(NixiState.NixiEvent.ToolStarted(name, args.toString().take(300)))
            LogBus.log("tool.start", name)
            lastUserActivity = System.currentTimeMillis()

            val result: ToolResult = if (ToolRegistry.DANGEROUS.contains(name)) {
                val pendingId = UUID.randomUUID().toString()
                val desc = describeDangerous(name, args)
                NixiState.pendingActions.value =
                    NixiState.pendingActions.value +
                        NixiState.PendingAction(pendingId, "NIXI prosi o potwierdzenie", desc, name)
                // jeśli okno rozmowy jest schowane, użytkownik dowie się z powiadomienia
                ActionNotifier.notify(
                    this, "NIXI czeka na zgodę",
                    "$desc — otwórz NIXI, aby potwierdzić (samo zniknie po minucie).",
                    short = true
                )
                val approved = awaitDecision(pendingId)
                NixiState.pendingActions.value =
                    NixiState.pendingActions.value.filter { it.id != pendingId }
                if (!approved) {
                    ToolResult.fail("Użytkownik nie potwierdził tej operacji. Nie wykonuj jej.")
                } else {
                    ToolRegistry.dispatch(name, args)
                }
            } else {
                ToolRegistry.dispatch(name, args)
            }

            NixiState.emit(NixiState.NixiEvent.ToolDone(name, result.ok, result.text.take(220)))
            NixiState.lastToolLine.value = "$name: ${result.text.take(140)}"

            val resp = JSONObject().put("id", fcId).put("name", name)
            val payload = JSONObject()
            if (result.ok) payload.put("result", result.text) else payload.put("error", result.text)
            resp.put("response", payload)
            responses.put(resp)

            // zrzut ekranu (tryb ręczny) — klatka dla modelu PRZED odpowiedzią narzędzia
            result.imageB64?.let { img ->
                client?.sendImage(img)
                tpm.addUsed(TpmGuard.tokensForImage(1152, 2400))
            }

            if (!result.ok) {
                LogBus.log("tool.error", "$name: ${result.text.take(160)}", "warn")
            }
        }
        client?.sendToolResponse(responses)
        // odpowiedzi narzędzi też są tokenami po stronie modelu
        tpm.addUsed(TpmGuard.tokensForText(responses.toString()))
        publishTpm()
    }

    private suspend fun awaitDecision(pendingId: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        decisions[pendingId] = deferred
        val answer = withTimeoutOrNull(60_000) { deferred.await() } ?: false
        decisions.remove(pendingId)
        return answer
    }

    private fun describeDangerous(name: String, args: JSONObject): String = when (name) {
        "db_delete" -> "Usunąć wiersz ${args.optString("id", "")} z tabeli ${args.optString("table", "")}?"
        "calendar_remove" -> "Usunąć wydarzenie kalendarza #${args.optString("id", "")}?"
        "alarm_remove" -> "Wyłączyć budzik #${args.optString("id", "")}?"
        "reminder_remove" -> "Usunąć przypomnienie #${args.optString("id", "")}?"
        "rule_remove" -> "Usunąć regułę #${args.optString("id", "")}?"
        else -> "Wykonać akcję $name? ${args.toString().take(160)}"
    }

    private fun playDing(resId: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val mp = android.media.MediaPlayer.create(this@LiveSessionService, resId) ?: return@launch
                mp.setVolume(0.6f, 0.6f)
                mp.setOnCompletionListener { it.release() }
                mp.setOnErrorListener { m, _, _ -> runCatching { m.release() }; true }
                mp.start()
            } catch (_: Exception) {
            }
        }
    }

    private fun endSession(reason: String) {
        if (!ended.compareAndSet(false, true)) return
        LogBus.log("live.end", reason)
        running = false
        startGate.set(false)
        NixiApp.scope.launch {
            runCatching {
                val duration = (System.currentTimeMillis() - sessionStart) / 1000
                LocalStore.lastSessionDuration = duration
                // Diagnostyka „NIXI nie odpowiada”: dlaczego się skończyło,
                // ile klatek audio poszło i czy model w ogóle się odezwał.
                LocalStore.lastSessionReason = reason
                LocalStore.lastSessionAudioChunks = client?.sentAudioChunks ?: 0
                LocalStore.lastSessionAudioReplies = audioReplies
                LocalStore.lastSessionVariant = setupVariant
                LocalStore.lastSessionSetupOk = setupDone
                idleJob?.cancel()
                tpmJob?.cancel()
                // 1) domknij wszystko, co czeka na użytkownika
                cancelAllDecisions()
                NixiState.pendingActions.value = emptyList()
                NixiState.inSession.value = false
                NixiState.sessionTrusted.value = false
                NixiState.wantScreenCapture.value = false
                NixiState.manualMode.value = false
                NixiState.orbState.value = NixiState.OrbState.IDLE
                runCatching { dev.nixi.overlay.ConversationHost.hide() }
                // 2) dźwięk i sieć
                runCatching { AudioBus.disableEchoCancel() }
                runCatching { player.stop() }
                client?.close()
                client?.shutdown()
                client = null
                // 3) mikrofon: wróć do nasłuchu ALBO go zwolnij (nigdy nie trzymaj w tle)
                if (LocalStore.wakeEnabled && WakeWordService.running) {
                    WakeWordService.restoreWakeConsumer()
                } else if (!WakeWordService.running) {
                    AudioBus.stop()
                }
                // 4) muzyka
                runCatching { MediaPauseController.resumeAll() }
                if (LocalStore.dingEnabled) playDing(dev.nixi.R.raw.ding_stop)
                // 5) tryb ręczny zamykamy, ale zgoda MediaProjection zostaje
                //    (kolejna sesja nie pyta ponownie, dopóki usługa żyje).
                NixiState.manualMode.value = false
                ActionNotifier.notify(
                    this@LiveSessionService, "NIXI: rozmowa zakończona",
                    "Powód: $reason • czas: ${duration / 60} min ${duration % 60} s",
                    short = true
                )
                NixiState.emit(NixiState.NixiEvent.SessionEnded(reason, duration))
                publishTpm()
                // sesja = sieć działa; przy okazji nadgonić zaległe zapisy
                runCatching { dev.nixi.db.OfflineQueue.flush(this@LiveSessionService) }
                // 6) pamięć długotrwała: podsumowanie + fakty (tylko przy realnej rozmowie)
                val turns = transcripts.count { it.first == "user" }
                if (turns >= 2 && LocalStore.geminiKey.isNotBlank()) {
                    PostSessionMemory.run(this@LiveSessionService, transcripts.toList(), duration)
                }
                stopForegroundCompat()
                stopSelf()
            }.onFailure {
                LogBus.logException("live.end", it)
                runCatching { stopForegroundCompat() }
                runCatching { stopSelf() }
            }
        }
    }

    private fun stopForegroundCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun startForegroundCompat(title: String, text: String): Boolean {
        val notif = ActionNotifier.fgsNotification(title, text, NOTIF_ID)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                return true
            } catch (t: Throwable) {
                LogBus.log("live.fgs", "mikrofon FGS: ${t.message}", "warn")
            }
            // HyperOS/Android 14: drugi FGS mikrofonu z tła. Spróbuj bez typu,
            // potem jako „connectedDevice” nie — zostaje zwykły startForeground.
            try {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notif)
                return true
            } catch (t: Throwable) {
                LogBus.log("live.fgs", "startForeground nieudany: ${t.message}", "error")
                return false
            }
        }
        return try {
            startForeground(NOTIF_ID, notif)
            true
        } catch (t: Throwable) {
            LogBus.log("live.fgs", "startForeground nieudany: ${t.message}", "error")
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        running = false
        startGate.set(false)
        // Sprzątanie awaryjne (gdy system zabije usługę bez endSession)
        if (!ended.get()) {
            ended.set(true)
            idleJob?.cancel()
            tpmJob?.cancel()
            cancelAllDecisions()
            NixiState.pendingActions.value = emptyList()
            NixiState.inSession.value = false
            NixiState.sessionTrusted.value = false
            NixiState.wantScreenCapture.value = false
            NixiState.manualMode.value = false
            NixiState.orbState.value = NixiState.OrbState.IDLE
            runCatching { player.stop() }
            runCatching { client?.shutdown() }
            client = null
            if (LocalStore.wakeEnabled && WakeWordService.running) {
                WakeWordService.restoreWakeConsumer()
            } else {
                runCatching { AudioBus.stop() }
            }
            runCatching { MediaPauseController.resumeAll() }
            runCatching { ScreenCaptureService.stop(this) }
        }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
