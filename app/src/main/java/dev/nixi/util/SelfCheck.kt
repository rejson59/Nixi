package dev.nixi.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.nixi.NixiState
import dev.nixi.accessibility.NixiAccessibilityService
import dev.nixi.db.DbProvisioner
import dev.nixi.db.SupabaseHub
import dev.nixi.notif.NixiNotificationListener
import dev.nixi.store.LocalStore
import dev.nixi.wake.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SAMOKONTROLA NIXI — jedno miejsce, które mówi wprost, co działa, a co nie.
 *
 * Powstała po zgłoszeniu „NIXI nie odpowiada, a w ustawieniach pisze, że
 * usługa dostępności nie działa”: zamiast zgadywać, aplikacja sprawdza po
 * kolei klucz API, nazwę modelu, uprawnienia i bazę, i pokazuje wynik razem
 * z podpowiedzią, co zrobić.
 *
 * Wszystko dzieje się lokalnie; jedyne zapytanie na zewnątrz to lista modeli
 * Gemini (żeby sprawdzić klucz i nazwę modelu).
 */
object SelfCheck {

    enum class Level { OK, WARN, ERR }

    data class Item(
        val label: String,
        val level: Level,
        val detail: String,
        val fix: String = "",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun run(context: Context): List<Item> {
        NixiState.selfCheckRunning.value = true
        val items = ArrayList<Item>()
        try {
            val key = LocalStore.geminiKey
            if (key.isBlank()) {
                items.add(
                    Item(
                        "Klucz API Gemini", Level.ERR, "brak klucza",
                        "Wklej klucz z aistudio.google.com/apikey w Ustawieniach → Mózg."
                    )
                )
            } else {
                items.addAll(checkGemini(key))
                // Ostatni i najważniejszy krok: prawdziwa próba otwarcia
                // rozmowy. Odpowiada wprost na pytanie „dlaczego NIXI milczy”.
                items.add(checkLiveSession(key))
            }
            items.add(checkMic(context))
            items.add(checkSpeaker(context))
            items.add(checkWake())
            items.add(checkSupabase())
            items.add(checkAccessibility(context))
            items.add(checkNotifications(context))
        } catch (t: Throwable) {
            items.add(Item("Samokontrola", Level.ERR, t.message ?: "nieznany błąd"))
        } finally {
            NixiState.selfCheck.value = items
            NixiState.selfCheckRunning.value = false
        }
        return items
    }

    /** Klucz + model: jedno zapytanie do listy modeli odpowiada na oba pytania. */
    private suspend fun checkGemini(key: String): List<Item> = withContext(Dispatchers.IO) {
        val out = ArrayList<Item>()
        val model = LocalStore.geminiModel
        try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=" + key.trim())
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    out.add(
                        Item(
                            "Klucz API Gemini", Level.ERR, "serwer odpowiedział ${resp.code}",
                            problemHint(resp.code, text)
                        )
                    )
                    return@withContext out
                }
                out.add(Item("Klucz API Gemini", Level.OK, "klucz działa"))

                val names = ArrayList<String>()
                val live = ArrayList<String>()
                runCatching {
                    val arr = JSONObject(text).optJSONArray("models")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val m = arr.getJSONObject(i)
                            val name = m.optString("name", "").removePrefix("models/")
                            if (name.isNotBlank()) names.add(name)
                            val methods = m.optJSONArray("supportedGenerationMethods")
                            var liveOk = false
                            if (methods != null) {
                                for (j in 0 until methods.length()) {
                                    if (methods.optString(j) == "bidiGenerateContent") liveOk = true
                                }
                            }
                            if (liveOk && name.isNotBlank()) live.add(name)
                        }
                    }
                }
                if (names.isEmpty()) {
                    out.add(Item("Model Live", Level.WARN, "nie mogę odczytać listy modeli"))
                } else if (live.contains(model)) {
                    out.add(Item("Model Live", Level.OK, "$model obsługuje rozmowę głosową"))
                } else if (names.contains(model)) {
                    out.add(
                        Item(
                            "Model Live", Level.ERR, "$model nie obsługuje rozmowy głosowej",
                            "Ten model nie ma trybu Live. Wybierz: " + live.take(3).joinToString(", ")
                        )
                    )
                } else {
                    out.add(
                        Item(
                            "Model Live", Level.ERR, "nie widzę modelu „$model”",
                            "Dostępne modele Live: " + (if (live.isEmpty()) "brak" else live.take(3).joinToString(", "))
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            out.add(
                Item(
                    "Klucz API Gemini", Level.ERR, "brak połączenia: ${t.message}",
                    "Sprawdź internet i spróbuj ponownie."
                )
            )
        }
        out
    }

    /**
     * Próba otwarcia prawdziwej sesji Live (bez wysyłania audio): jeśli
     * serwer odpowie `setupComplete`, rozmowa działa; jeśli przyjdzie błąd —
     * pokazujemy jego treść, bo tam jest cała odpowiedź (zły klucz, brak
     * dostępu do modelu, zła konfiguracja).
     */
    private fun checkLiveSession(key: String): Item {
        val model = LocalStore.geminiModel
        val variant = LocalStore.liveSetupVariant
        val label = "Rozmowa z Gemini"
        return try {
            val url = "wss://generativelanguage.googleapis.com/ws/google.ai." +
                "generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
                "?key=" + java.net.URLEncoder.encode(key.trim(), "UTF-8")
            val setup = testSetup(variant)
            val latch = CountDownLatch(1)
            val outcome = java.util.concurrent.atomic.AtomicReference("")
            val ws = http.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(setup.toString())
                    }

                    // Gemini wysyła raz tekst, raz binarnie — patrzymy na oba
                    override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) =
                        handle(bytes.utf8())

                    private fun handle(message: String) {
                        when {
                            message.contains("setupComplete") -> {
                                outcome.set("ok")
                                latch.countDown()
                            }
                            message.contains("\"error\"") -> {
                                outcome.set("serwer: " + message.take(240))
                                latch.countDown()
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        outcome.set(
                            "brak połączenia: ${t.message ?: "?"}" +
                                (response?.let { " (${it.code})" } ?: "")
                        )
                        latch.countDown()
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        if (outcome.get().isEmpty()) {
                            outcome.set("zamknięto ($code) ${reason.take(160)}")
                            latch.countDown()
                        }
                    }
                }
            )
            val got = latch.await(12, TimeUnit.SECONDS)
            runCatching { ws.cancel() }
            val result = outcome.get()
            when {
                !got -> Item(label, Level.ERR, "brak odpowiedzi w 12 s", "Sprawdź internet.")
                result == "ok" -> Item(label, Level.OK, "sesja otwarta ($model, wariant $variant)")
                else -> Item(
                    label, Level.ERR, result,
                    "To jest powód ciszy. Jeśli w treści jest „model”, zmień nazwę modelu " +
                        "w Ustawieniach → Mózg; jeśli „API key”, wklej klucz ponownie."
                )
            }
        } catch (t: Throwable) {
            Item(label, Level.ERR, "test nie wyszedł: ${t.message}", "Sprawdź internet i spróbuj ponownie.")
        }
    }

    /**
     * Ten sam kształt `setup`, co w rozmowie (z narzędziami — bo to one
     * najczęściej są przyczyną odrzucenia), ale bez pełnej instrukcji
     * systemowej: test ma być szybki i tani.
     */
    private fun testSetup(variant: Int): JSONObject {
        val setup = JSONObject()
            .put("model", "models/" + LocalStore.geminiModel)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    org.json.JSONArray().put(JSONObject().put("text", "Odpowiedz jednym słowem: test."))
                )
            )
            .put(
                "tools",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "functionDeclarations",
                        dev.nixi.live.ToolRegistry.declarations()
                    )
                )
            )
        val speech = JSONObject().put(
            "voiceConfig",
            JSONObject().put(
                "prebuiltVoiceConfig",
                JSONObject().put("voiceName", LocalStore.voiceName.ifBlank { "Puck" })
            )
        )
        when (variant) {
            0 -> setup.put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                    .put("speechConfig", speech)
            )
            1 -> setup
                .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                .put("speechConfig", speech)
        }
        return JSONObject().put("setup", setup)
    }

    private fun problemHint(code: Int, body: String): String = when (code) {
        400 -> "Klucz wygląda na niepoprawny. Skopiuj go ponownie z aistudio.google.com/apikey."
        401, 403 -> "Klucz nie ma dostępu do Gemini API — sprawdź, czy API jest włączone w projekcie."
        429 -> "Limit zapytań — poczekaj minutę i spróbuj ponownie."
        else -> body.take(160)
    }

    /**
     * Test mikrofonu z prawdziwym nasłuchem: 1,5 s zapisu (albo poziom
     * z działającego już nasłuchu „Hej Nixi”). To odróżnia „uprawnienie
     * jest” od „mikrofon realnie coś słyszy” — a właśnie to drugie decyduje
     * o tym, czy NIXI odpowie.
     */
    private suspend fun checkMic(context: Context): Item = withContext(Dispatchers.IO) {
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return@withContext Item(
                "Mikrofon", Level.ERR, "brak uprawnienia",
                "Ustawienia systemowe → Aplikacje → NIXI → Uprawnienia → Mikrofon."
            )
        }

        // Nasłuch już zbiera dźwięk — nie otwieramy drugiego mikrofonu.
        if (dev.nixi.audio.AudioBus.isRunning()) {
            var peak = 0f
            val end = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < end) {
                peak = maxOf(peak, NixiState.micLevel.value)
                Thread.sleep(80)
            }
            val pct = (peak * 100).toInt()
            return@withContext if (peak > 0.04f) {
                Item("Mikrofon", Level.OK, "słyszę dźwięk (poziom ${pct}%)")
            } else {
                Item(
                    "Mikrofon", Level.WARN, "otwarty, ale cisza (poziom ${pct}%)",
                    "Powiedz coś głośno w trakcie tego testu. Jeśli nadal 0% — " +
                        "sprawdź, czy mikrofon nie jest wyciszony systemowo (albo zajęty przez inną aplikację)."
                )
            }
        }

        val rec = try {
            val min = android.media.AudioRecord.getMinBufferSize(
                16000,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, 6400)
            )
        } catch (t: Throwable) {
            null
        }
        if (rec == null || rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
            runCatching { rec?.release() }
            return@withContext Item(
                "Mikrofon", Level.ERR, "nie mogę otworzyć mikrofonu",
                "Prawdopodobnie trwa rozmowa telefoniczna albo inna aplikacja trzyma mikrofon. " +
                    "Zamknij ją i spróbuj ponownie."
            )
        }

        var peak = 0.0
        try {
            rec.startRecording()
            val buf = ShortArray(1600) // 100 ms
            val end = System.currentTimeMillis() + 1500
            var first = true
            while (System.currentTimeMillis() < end) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (first) {
                    first = false
                    continue // pierwsza klatka po starcie bywa zaszumiona
                }
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i] / 32768.0
                    sum += v * v
                }
                peak = maxOf(peak, kotlin.math.sqrt(sum / n))
            }
        } catch (t: Throwable) {
            runCatching { rec.release() }
            return@withContext Item("Mikrofon", Level.ERR, "błąd zapisu: ${t.message}")
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        val pct = (peak / 0.02 * 100).toInt().coerceAtMost(100)
        if (peak > 0.004) {
            Item("Mikrofon", Level.OK, "słyszę dźwięk (poziom ${pct}%)")
        } else {
            Item(
                "Mikrofon", Level.WARN, "otwarty, ale cisza (poziom ${pct}%)",
                "Powiedz coś w trakcie testu. Jeśli nadal 0% — sprawdź wyciszenie mikrofonu " +
                    "albo czy nie trzyma go inna aplikacja."
            )
        }
    }

    /**
     * Test wyjścia audio: czy telefon w ogóle może odtworzyć głos NIXI.
     * Sprawdzamy głośność strumienia multimediów i to, czy AudioTrack
     * przyjmuje dane. Bez tego „cisza” bywa po prostu wyciszonym telefonem.
     */
    private fun checkSpeaker(context: Context): Item {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val vol = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
        val max = am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
        if (vol == 0) {
            return Item(
                "Głośnik", Level.WARN, "multimedia wyciszone (0/$max)",
                "Podgłoś telefon — przy wyciszonym strumieniu multimediów nie usłyszysz NIXI."
            )
        }
        val ok = try {
            val min = android.media.AudioTrack.getMinBufferSize(
                24000,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            val t = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(24000)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min * 2, 96000))
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()
            val silence = ByteArray(4800) // 100 ms ciszy — test bez dźwięku
            t.play()
            val written = t.write(silence, 0, silence.size)
            runCatching { t.stop() }
            runCatching { t.release() }
            written > 0
        } catch (_: Throwable) {
            false
        }
        return if (ok) Item("Głośnik", Level.OK, "wyjście audio gotowe (głośność $vol/$max)")
        else Item(
            "Głośnik", Level.ERR, "nie mogę odtworzyć dźwięku",
            "Sprawdź, czy żadna aplikacja nie blokuje wyjścia audio, i spróbuj ponownie."
        )
    }

    private fun checkWake(): Item {
        if (!LocalStore.wakeEnabled) {
            return Item("Nasłuch „Hej Nixi”", Level.WARN, "wyłączony", "Włącz w Ustawieniach → Nasłuch.")
        }
        if (LocalStore.wakeNeedsEnroll) {
            return Item(
                "Nasłuch „Hej Nixi”", Level.ERR, "brak wzorca frazy",
                "Nagraj „Hej Nixi” w Ustawieniach → Nasłuch (3 próby)."
            )
        }
        return if (WakeWordService.running) {
            Item("Nasłuch „Hej Nixi”", Level.OK, "działa (${LocalStore.wakeCpuMsPerMin} ms CPU/min)")
        } else {
            Item(
                "Nasłuch „Hej Nixi”", Level.WARN, "usługa nie działa",
                "Otwórz aplikację (usługa startuje razem z nią) albo włącz nasłuch ponownie."
            )
        }
    }

    private fun checkSupabase(): Item {
        val url = LocalStore.supabaseUrl
        if (url.isBlank()) {
            return Item(
                "Supabase", Level.WARN, "nie podłączony",
                "Wklej URL i klucz anon w Ustawieniach → Dane (działa bez tego, ale bez pamięci i tabel)."
            )
        }
        if (!SupabaseHub.available) {
            return Item(
                "Supabase", Level.ERR, "brak połączenia z projektem",
                "Sprawdź URL i klucz anon — i czy tabele zostały utworzone (Ustawienia → Dane → Struktura bazy)."
            )
        }
        val status = DbProvisioner.status.value
        return when {
            status == null -> Item("Supabase", Level.OK, "połączony")
            status.missing.isEmpty() -> Item("Supabase", Level.OK, "połączony, ${status.present.size} tabel gotowych")
            else -> Item(
                "Supabase", Level.WARN,
                "brakuje tabel: ${status.missing.take(4).joinToString(", ")}" +
                    (if (status.missing.size > 4) "…" else ""),
                "Ustawienia → Dane → „Utwórz / zaktualizuj tabele”."
            )
        }
    }

    private fun checkAccessibility(context: Context): Item {
        val ready = NixiAccessibilityService.isAvailable()
        val enabled = NixiAccessibilityService.isEnabledInSystem(context)
        return when {
            ready -> Item("Usługa dostępności", Level.OK, "włączona i gotowa")
            enabled -> Item(
                "Usługa dostępności", Level.WARN, "włączona w systemie, czeka na połączenie",
                "Zamknij i otwórz aplikację — usługa połączy się sama. Jeśli nie, wyłącz ją i włącz ponownie."
            )
            else -> Item(
                "Usługa dostępności", Level.WARN, "wyłączona (potrzebna tylko do trybu ręcznego)",
                "Ustawienia systemowe → Dostępność → NIXI."
            )
        }
    }

    private fun checkNotifications(context: Context): Item =
        if (NixiNotificationListener.isEnabled(context)) {
            Item("Dostęp do powiadomień", Level.OK, "włączony")
        } else {
            Item(
                "Dostęp do powiadomień", Level.WARN, "wyłączony",
                "Bez tego nie ma cichych reguł, raportów i pauzy muzyki."
            )
        }
}
