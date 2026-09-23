package dev.nixi.live

import android.app.Service
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 */
class LiveSessionService : Service() {

    companion object {
        const val NOTIF_ID = 1002
        const val EXTRA_TRIGGER = "trigger"

        @Volatile var running = false
            private set

        @Volatile var instance: LiveSessionService? = null
            private set

        private val decisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

        fun start(context: Context, trigger: String) {
            if (running) return
            val intent = Intent(context, LiveSessionService::class.java)
                .putExtra(EXTRA_TRIGGER, trigger)
            context.startForegroundService(intent)
        }

        /** Odpowiedź na okno potwierdzenia (UI okna rozmowy). */
        fun confirm(pendingId: String, approved: Boolean) {
            decisions[pendingId]?.complete(approved)
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
    @Volatile private var lastActivity = 0L
    @Volatile private var throttled = false

    override fun onCreate() {
        super.onCreate()
        ToolContext.app = NixiApp.app
        ToolContext.screenWidthPx = resources.displayMetrics.widthPixels
        ToolContext.screenHeightPx = resources.displayMetrics.heightPixels
        instance = this
        if (sessionStarted.compareAndSet(false, true)) {
            beginSession()
        }
    }

    /** Użytkownik wyraził zgodę na podgląd ekranu (MediaProjection). */
    fun onScreenConsent() {
        val c = client ?: return
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        c.sendText(
            "Użytkownik wyraził zgodę na podgląd ekranu (rozdzielczość ${w}x$h). " +
                "Tryb ręczny jest gotowy — użyj screen_get, aby zobaczyć ekran, i wykonaj zadanie. " +
                "Po wykonaniu zadania zakończ tryb przez screen_manual_stop."
        )
        LogBus.log("manual.consent", "zgoda na podgląd ekranu")
    }

    /** Szybkie polecenie tekstowe od UI (np. „uruchom tryb ręczny”). */
    fun sendUserText(text: String) {
        client?.sendText(text)
    }

    private fun beginSession() {
        val trigger = "button"
        running = true
        NixiState.inSession.value = true
        NixiState.lastToolLine.value = ""
        sessionStart = System.currentTimeMillis()
        lastActivity = sessionStart
        startForegroundCompat("NIXI rozmawia", "Dotknij, aby otworzyć okno rozmowy")

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
            val setup = buildSetup(prompt)
            c.connect(setup)
            streamAudio()
            idleWatcher()
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
        setup.put(
            "realtimeInputConfig",
            JSONObject().put(
                "automaticActivityDetection",
                JSONObject()
                    .put("endOfActivityAudioMs", 900)
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
                    return
                }
                throttled = false
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
                lastActivity = System.currentTimeMillis()
                if (level > 0.3f) {
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
                val idle = System.currentTimeMillis() - lastActivity
                val inManual = NixiState.manualMode.value
                if (idle > 5 * 60_000 && !inManual) {
                    endSession("bezczynność (5 min)")
                    break
                }
            }
        }
    }

    private val listener = object : GeminiLiveClient.Listener {
        override fun onSetupComplete() {
            LogBus.log("live.setup", "sesja gotowa")
            NixiState.orbState.value = NixiState.OrbState.LISTENING
            if (LocalStore.dingEnabled) playDing(dev.nixi.R.raw.ding_start)
        }

        override fun onAudio(pcm: ByteArray) {
            player.write(pcm)
            if (NixiState.orbState.value != NixiState.OrbState.SPEAKING) {
                NixiState.orbState.value = NixiState.OrbState.SPEAKING
            }
            lastActivity = System.currentTimeMillis()
        }

        override fun onTextDelta(text: String) {
            textBuffer.append(text)
            if (textBuffer.length > 4000) textBuffer.delete(0, textBuffer.length - 4000)
        }

        override fun onInputTranscript(text: String) {
            if (text.isNotBlank()) transcripts.add("user" to text)
            lastActivity = System.currentTimeMillis()
        }

        override fun onOutputTranscript(text: String) {
            if (text.isNotBlank()) transcripts.add("nixi" to text)
        }

        override fun onToolCall(callId: String, calls: List<JSONObject>) {
            scope.launch { handleToolCalls(callId, calls) }
        }

        override fun onTurnComplete() {
            if (NixiState.orbState.value == NixiState.OrbState.SPEAKING) {
                NixiState.orbState.value = NixiState.OrbState.LISTENING
            }
        }

        override fun onGoAway() {
            LogBus.log("live.goaway", "serwer kończy sesję")
            endSession("goAway")
        }

        override fun onSessionEnd() {
            endSession("koniec sesji")
        }

        override fun onApiError(code: Int, message: String) {
            LogBus.log("live.error", "code=$code $message", "error")
            if (code == 429) {
                tpm.noteRateLimit()
                ActionNotifier.notify(
                    this@LiveSessionService, "NIXI: limit TPM",
                    "Zbyt wiele tokenów/min (limit darmowego planu). Odwrotnie za chwilę — spróbuj ponownie za ~60 s.",
                    short = false
                )
            } else if (code == 400 || code == 403) {
                ActionNotifier.notify(
                    this@LiveSessionService, "NIXI: błąd API",
                    "Gemini Live: ${message.take(200)}. Sprawdź klucz/model w Ustawieniach.",
                    short = false
                )
            }
            endSession("błąd API $code")
        }

        override fun onClosed(code: Int, reason: String) {
            if (!ended.get()) endSession("zamknięto ($code $reason)")
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

            val result: ToolResult = if (ToolRegistry.DANGEROUS.contains(name)) {
                val pendingId = UUID.randomUUID().toString()
                val desc = describeDangerous(name, args)
                NixiState.pendingActions.value =
                    NixiState.pendingActions.value +
                        NixiState.PendingAction(pendingId, "NIXI prosi o potwierdzenie", desc, name)
                val approved = awaitDecision(pendingId)
                NixiState.pendingActions.value =
                    NixiState.pendingActions.value.filter { it.id != pendingId }
                if (!approved) {
                    ToolResult.fail("Użytkownik odrzucił tę operację. Nie wykonuj jej.")
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

            // zrzut ekranu (tryb ręczny) — wysyłamy do modelu jako klatkę wideo
            result.imageB64?.let { client?.sendImage(it) }

            if (!result.ok) {
                dev.nixi.util.LogBus.log("tool.error", "$name: ${result.text.take(160)}", "warn")
            }
        }
        client?.sendToolResponse(responses)
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
                val mp = android.media.MediaPlayer.create(this@LiveSessionService, resId)
                mp.setVolume(0.6f, 0.6f)
                mp.setOnCompletionListener { it.release() }
                mp.start()
            } catch (_: Exception) {
            }
        }
    }

    private fun endSession(reason: String) {
        if (!ended.compareAndSet(false, true)) return
        LogBus.log("live.end", reason)
        NixiApp.scope.launch {
            runCatching {
                val duration = (System.currentTimeMillis() - sessionStart) / 1000
                LocalStore.lastSessionDuration = duration
                NixiState.inSession.value = false
                NixiState.orbState.value = NixiState.OrbState.IDLE
                NixiState.manualMode.value = false
                NixiState.pendingActions.value = emptyList()
                idleJob?.cancel()
                player.stop()
                client?.goAway()
                client?.shutdown()
                client = null
                // wróć do nasłuchu wake (jeśli aktywny)
                if (LocalStore.wakeEnabled && WakeWordService.running) {
                    WakeWordService.restoreWakeConsumer()
                }
                MediaPauseController.resumeAll()
                if (LocalStore.dingEnabled) playDing(dev.nixi.R.raw.ding_stop)
                ActionNotifier.notify(
                    this@LiveSessionService, "NIXI: rozmowa zakończona",
                    "Powód: $reason • czas: ${duration / 60} min ${duration % 60} s",
                    short = true
                )
                // tryb ręczny zawsze zamykamy z sesją
                ScreenCaptureService.stop(this@LiveSessionService)
                NixiState.emit(
                    NixiState.NixiEvent.SessionEnded(reason, duration)
                )
                // pamięć długotrwała: podsumowanie + fakty (tylko przy rozmowie > 3 wymiany)
                if (transcripts.size >= 4 && LocalStore.geminiKey.isNotBlank()) {
                    val copy = transcripts.toList()
                    PostSessionMemory.run(this@LiveSessionService, copy, duration)
                }
                stopForegroundCompat()
                stopSelf()
            }.onFailure { LogBus.logException("live.end", it) }
        }
    }

    private fun stopForegroundCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun startForegroundCompat(title: String, text: String) {
        val notif = ActionNotifier.fgsNotification(title, text, NOTIF_ID)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        if (sessionStarted.get()) {
            ended.set(true)
            running = false
            runCatching { player.stop() }
            runCatching { client?.shutdown() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
