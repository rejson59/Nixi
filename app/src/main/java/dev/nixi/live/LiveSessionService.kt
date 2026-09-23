package dev.nixi.live

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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

        @Volatile var running = false
            private set

        @Volatile var instance: LiveSessionService? = null
            private set

        /** Oczekujące potwierdzenia (id -> decyzja) — wspólne dla UI i usługi. */
        private val decisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

        fun start(context: Context, trigger: String) {
            if (running) {
                // sesja już trwa — nie zaczynamy drugiej
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
            val intent = Intent(context, LiveSessionService::class.java)
                .putExtra(EXTRA_TRIGGER, trigger)
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                // Android 12+: start FGS z tła bywa zabroniony (np. z BootReceivera)
                LogBus.log("live.start", "nie mogę wystartować usługi: ${t.message}", "error")
                ActionNotifier.notify(
                    context, "NIXI",
                    "System nie pozwolił uruchomić sesji w tle. Otwórz aplikację i spróbuj ponownie.",
                    short = false
                )
            }
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
            // np. Android 14+ nie pozwolił na mikrofon w tle — nie zaczynamy sesji
            running = false
            ActionNotifier.notify(
                this, "NIXI",
                "System nie pozwolił rozpocząć rozmowy w tle. Otwórz aplikację i spróbuj z niej.",
                short = false
            )
            stopSelf()
            return START_NOT_STICKY
        }
        beginSession()
        return START_NOT_STICKY
    }

    /** Użytkownik wyraził zgodę na podgląd ekranu (MediaProjection). */
    fun onScreenConsent() {
        val c = client ?: return
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        NixiState.orbState.value = NixiState.OrbState.MANUAL
        c.sendText(
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
        client?.sendText(
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
        c.sendText(text)
    }

    private fun beginSession() {
        NixiState.inSession.value = true
        NixiState.lastToolLine.value = ""
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

        player.start()
        val c = GeminiLiveClient(key, listener)
        client = c

        scope.launch {
            val prompt = withTimeoutOrNull(6000) { SystemPromptBuilder.build() }
                ?: "Jesteś NIXI, osobistą asystentką. Mów po polsku, zwięźle."
            if (ended.get()) return@launch
            val setup = buildSetup(prompt)
            c.connect(setup)
            streamAudio()
            idleWatcher()
            tpmWatcher()
        }
    }

    private fun buildSetup(prompt: String): JSONObject {
        val setup = JSONObject()
            .put("model", "models/${LocalStore.geminiModel}")
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            )
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", ToolRegistry.declarations())))
        setup.put(
            "speechConfig",
            JSONObject()
                .put(
                    "voiceConfig",
                    JSONObject().put(
                        "prebuiltVoiceConfig",
                        JSONObject().put("voiceName", LocalStore.voiceName.ifBlank { "Puck" })
                    )
                )
                .put(
                    "audioConfig",
                    JSONObject()
                        .put("audioEncoding", "LINEAR16")
                        .put("sampleRateHertz", 24000)
                )
        )
        // tekst rozmowy (user/nixi) do podsumowań i pamięci — serwer wysyła tylko po włączeniu
        setup.put("inputAudioTranscription", JSONObject())
        setup.put("outputAudioTranscription", JSONObject())
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

    /** Strumień mikrofonu do Live API (z TpmGuard i barge-in). */
    private fun streamAudio() {
        val consumer = object : AudioBus.Consumer {
            override fun onPcm(pcm: ShortArray, len: Int) {
                val c = client ?: return
                if (!c.isOpen()) return
                val sec = len / 16000.0
                val tokens = TpmGuard.tokensForAudioSeconds(sec)
                if (!tpm.canSend(tokens)) {
                    if (!throttled) {
                        throttled = true
                        NixiState.orbState.value = NixiState.OrbState.TPM_LIMIT
                        NixiState.emit(NixiState.NixiEvent.TpmLimited("Limit TPM — chwilowa pauza"))
                        LogBus.log("tpm.throttle", "pauza nadawania", "warn")
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
                // UWAGA: poziom leci ~15x/s, więc NIE może odświeżać licznika
                // bezczynności — inaczej sesja nigdy się nie kończy.
                if (level > 0.3f) {
                    lastUserActivity = System.currentTimeMillis()
                    // barge-in: przerwij głos NIXI, gdy zacząłem mówić
                    if (NixiState.orbState.value == NixiState.OrbState.SPEAKING) {
                        player.flush()
                        NixiState.orbState.value = NixiState.OrbState.LISTENING
                    }
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
                val idle = System.currentTimeMillis() - lastUserActivity
                val inManual = NixiState.manualMode.value
                if (idle > 5 * 60_000 && !inManual) {
                    endSession("bezczynność (5 min)")
                    break
                }
                if (idle > 60_000 && !inManual && !manuallyReminded && NixiState.orbState.value != NixiState.OrbState.SPEAKING) {
                    manuallyReminded = true
                    ActionNotifier.notify(
                        this@LiveSessionService, "NIXI",
                        "Sesja wciąż działa, ale nic nie mówisz. Powiedz „koniec” albo dotknij kuli, aby zakończyć.",
                        short = true
                    )
                }
            }
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
            LogBus.log("live.setup", "sesja gotowa (trigger=$trigger)")
            NixiState.orbState.value = NixiState.OrbState.LISTENING
            if (LocalStore.dingEnabled) playDing(dev.nixi.R.raw.ding_start)
        }

        override fun onAudio(pcm: ByteArray) {
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
                lastUserActivity = System.currentTimeMillis()
            }
        }

        override fun onOutputTranscript(text: String) {
            if (text.isNotBlank()) transcripts.add("nixi" to text)
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
            LogBus.log("live.goaway", "serwer kończy sesję")
            endSession("serwer zakończył sesję")
        }

        override fun onSessionEnd() {
            endSession("koniec sesji")
        }

        override fun onApiError(code: Int, message: String) {
            LogBus.log("live.error", "code=$code $message", "error")
            if (code == 429) {
                tpm.noteRateLimit()
                publishTpm()
                ActionNotifier.notify(
                    this@LiveSessionService, "NIXI: limit tokenów",
                    "Zbyt wiele tokenów na minutę (limit darmowego planu). Odczekaj ~60 s i spróbuj ponownie.",
                    short = false
                )
            } else if (code == 400 || code == 401 || code == 403) {
                ActionNotifier.notify(
                    this@LiveSessionService, "NIXI: błąd API",
                    "Gemini Live: ${message.take(200)}. Sprawdź klucz/model w Ustawieniach.",
                    short = false
                )
            }
            endSession("błąd API $code")
        }

        override fun onClosed(code: Int, reason: String) {
            if (!ended.get()) endSession("zamknięto ($code ${reason.take(60)})")
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
        NixiApp.scope.launch {
            runCatching {
                val duration = (System.currentTimeMillis() - sessionStart) / 1000
                LocalStore.lastSessionDuration = duration
                idleJob?.cancel()
                tpmJob?.cancel()
                // 1) domknij wszystko, co czeka na użytkownika
                cancelAllDecisions()
                NixiState.pendingActions.value = emptyList()
                NixiState.inSession.value = false
                NixiState.manualMode.value = false
                NixiState.orbState.value = NixiState.OrbState.IDLE
                // 2) dźwięk i sieć
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
                // 5) tryb ręczny zawsze zamykamy z sesją
                runCatching { ScreenCaptureService.stop(this@LiveSessionService) }
                ActionNotifier.notify(
                    this@LiveSessionService, "NIXI: rozmowa zakończona",
                    "Powód: $reason • czas: ${duration / 60} min ${duration % 60} s",
                    short = true
                )
                NixiState.emit(NixiState.NixiEvent.SessionEnded(reason, duration))
                publishTpm()
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

    private fun startForegroundCompat(title: String, text: String): Boolean = try {
        val notif = ActionNotifier.fgsNotification(title, text, NOTIF_ID)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        true
    } catch (t: Throwable) {
        LogBus.log("live.fgs", "startForeground nieudany: ${t.message}", "error")
        false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        running = false
        // Sprzątanie awaryjne (gdy system zabije usługę bez endSession)
        if (!ended.get()) {
            ended.set(true)
            idleJob?.cancel()
            tpmJob?.cancel()
            cancelAllDecisions()
            NixiState.pendingActions.value = emptyList()
            NixiState.inSession.value = false
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
