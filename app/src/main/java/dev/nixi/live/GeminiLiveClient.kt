package dev.nixi.live

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import dev.nixi.util.LogBus

/**
 * Klient WebSocket Gemini Live API (BidiGenerateContent).
 *
 * Format (zgodny z aktualną dokumentacją):
 *  setup        -> {"setup": {"model":"models/gemini-3.8-live",
 *                  "responseModalities":["AUDIO"], "systemInstruction":...,
 *                  "tools":[{"functionDeclarations":[...]}],
 *                  "speechConfig":..., "realtimeInputConfig":...}}
 *  audio in     -> {"realtimeInput":{"audio":{"data":b64,"mimeType":"audio/pcm;rate=16000"}}}
 *  image in     -> {"realtimeInput":{"video":{"data":b64,"mimeType":"image/jpeg"}}}
 *  tool call    <- {"toolCall":{"id":..., "functionCalls":[{"id","name","args"}]}}
 *  tool response-> {"toolResponse":{"functionResponses":[{"id","name","response"}]}}
 *  goAway / sessionEnd / setupComplete / serverContent.modelTurn.parts[].inlineData
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val listener: Listener,
) {

    interface Listener {
        fun onSetupComplete() {}
        fun onAudio(pcm: ByteArray) {}
        fun onTextDelta(text: String) {}
        fun onInputTranscript(text: String) {}
        fun onOutputTranscript(text: String) {}
        fun onToolCall(callId: String, calls: List<JSONObject>) {}
        fun onTurnComplete() {}
        fun onGoAway() {}
        fun onSessionEnd() {}
        fun onApiError(code: Int, message: String) {}
        fun onClosed(code: Int, reason: String) {}
    }

    private val wsClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nixi-ws-send").apply { isDaemon = true }
    }
    private var ws: WebSocket? = null
    private val open = AtomicBoolean(false)
    @Volatile var closedByUs = false
        private set

    fun connect(setup: JSONObject) {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai." +
            "generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
            "?key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"
        ws = wsClient.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    open.set(true)
                    webSocket.send(setup.toString())
                    LogBus.log("live.ws", "połączono, setup wysłany")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                // Gemini Live potrafi wysłać JSON jako ramkę binarną (np. przy dużych
                // odpowiedziach z inlineData) — obsługujemy oba warianty.
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    handleMessage(bytes.utf8())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    open.set(false)
                    listener.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    open.set(false)
                    val code = response?.code ?: -1
                    LogBus.log("live.ws", "failure: ${t.message}", "error")
                    if (code in 400..599) {
                        listener.onApiError(code, t.message ?: "ws failure")
                    } else {
                        listener.onApiError(-1, t.message ?: "ws failure")
                    }
                }
            }
        )
    }

    private fun handleMessage(raw: String) {
        val msg = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }

        if (msg.has("setupComplete")) {
            listener.onSetupComplete()
            return
        }
        if (msg.has("goAway")) {
            listener.onGoAway()
            return
        }
        if (msg.has("sessionEnd")) {
            listener.onSessionEnd()
            return
        }

        val err = msg.optJSONObject("error")
        if (err != null) {
            val code = err.optInt("code", 0)
            listener.onApiError(code, err.optString("message", "unknown error"))
            return
        }

        val sc = msg.optJSONObject("serverContent")
        if (sc != null) {
            val modelTurn = sc.optJSONObject("modelTurn")
            if (modelTurn != null) {
                val parts = modelTurn.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val inline = part.optJSONObject("inlineData")
                        if (inline != null) {
                            val mime = inline.optString("mime_type", inline.optString("mimeType", ""))
                            val data = inline.optString("data")
                            if (mime.startsWith("audio/")) {
                                try {
                                    listener.onAudio(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
                                } catch (_: Exception) {
                                }
                            }
                        }
                        val txt = part.optString("text", "")
                        if (txt.isNotEmpty()) listener.onTextDelta(txt)
                    }
                }
            }
            sc.optJSONObject("inputTranscription")?.let {
                listener.onInputTranscript(it.optString("text", ""))
            }
            sc.optJSONObject("outputTranscription")?.let {
                listener.onOutputTranscript(it.optString("text", ""))
            }
            if (sc.optBoolean("turnComplete")) listener.onTurnComplete()
            return
        }

        // toolCall (nowy format) albo toolCallChunk (starszy)
        val tool = msg.optJSONObject("toolCall") ?: msg.optJSONObject("toolCallChunk")
        if (tool != null) {
            val callId = tool.optString("id", "")
            val fcs = tool.optJSONArray("functionCalls")
            if (fcs != null) {
                val calls = ArrayList<JSONObject>(fcs.length())
                for (i in 0 until fcs.length()) {
                    val fc = fcs.getJSONObject(i)
                    val normalized = JSONObject()
                    val inner = fc.optJSONObject("function")
                    if (inner != null) {
                        normalized.put("id", fc.optString("id", callId.ifBlank { "fc_$i" }))
                        normalized.put("name", inner.optString("name", fc.optString("name", "")))
                        normalized.put("args", inner.optJSONObject("args") ?: fc.optJSONObject("args") ?: JSONObject())
                    } else {
                        normalized.put("id", fc.optString("id", callId.ifBlank { "fc_$i" }))
                        normalized.put("name", fc.optString("name", ""))
                        normalized.put("args", fc.optJSONObject("args") ?: JSONObject())
                    }
                    calls.add(normalized)
                }
                if (calls.isNotEmpty()) listener.onToolCall(callId, calls)
            }
        }
    }

    private fun send(obj: JSONObject) {
        if (!open.get()) return
        sendExecutor.execute {
            runCatching { ws?.send(obj.toString()) }
                .onFailure { LogBus.log("live.ws", "send fail: ${it.message}", "warn") }
        }
    }

    fun sendAudio(base64Pcm: String) {
        sentAudioChunks++
        send(
            JSONObject().put("realtimeInput", JSONObject().put("audio",
                JSONObject().put("data", base64Pcm)
                    .put("mimeType", "audio/pcm;rate=16000")))
        )
    }

    fun sendText(text: String) {
        send(JSONObject().put("realtimeInput", JSONObject().put("text", text)))
    }

    fun sendImage(base64Jpeg: String) {
        send(
            JSONObject().put("realtimeInput", JSONObject().put("video",
                JSONObject().put("data", base64Jpeg)
                    .put("mimeType", "image/jpeg")))
        )
    }

    fun sendToolResponse(functionResponses: JSONArray) {
        send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", functionResponses)))
    }

    /**
     * Uwaga: `interrupt`, `goAway` i `sessionUpdate` NIE są komunikatami
     * klienta w BidiGenerateContent — wysyłanie ich kończyło się błędem
     * protokołu i zrywało sesję. Przerwanie odpowiedzi modelu robi się
     * lokalnie (patrz AudioPlayer.flush) oraz przez automatyczne VAD.
     * Poprawnym zakończeniem jest [close].
     */
    fun close() {
        closedByUs = true
        open.set(false)
        runCatching { ws?.close(1000, "client close") }
        runCatching { ws = null }
    }

    /** Ile bajtów/wiadomości wysłano (diagnostyka). */
    @Volatile var sentAudioChunks = 0
        private set

    fun shutdown() {
        close()
        sendExecutor.shutdownNow()
    }

    fun isOpen(): Boolean = open.get()
}
