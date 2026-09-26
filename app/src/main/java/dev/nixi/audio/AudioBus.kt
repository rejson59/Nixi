package dev.nixi.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.nixi.NixiApp
import dev.nixi.NixiState
import dev.nixi.util.LogBus
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.sqrt

/**
 * JEDEN wspólny rekord (16 kHz, mono, 16-bit) dla całej aplikacji.
 * Nasłuch wake-word i sesja głosowa przełączają [Consumer], dzięki czemu
 * telefon ma cały czas otwarty tylko jeden strumień mikrofonu (niskie
 * zużycie baterii — wątek blokuje się w read(), CPU obudza się ~15x/s).
 *
 * Stabilność:
 *  - `read()` zwracający błąd NIE zabija nasłuchu — rekord jest odtwarzany
 *    z backoffem (np. gdy mikrofon zabierze rozmowa telefoniczna),
 *  - po 5 nieudanych próbach stan jest raportowany do [NixiState] i logów,
 *  - `stop()` nigdy nie przerywa wątku (bez InterruptedException), tylko
 *    zamyka AudioRecord — pętla kończy się sama.
 */
object AudioBus {

    interface Consumer {
        /** Surowe PCM 16 kHz mono (len < pcm.size). */
        fun onPcm(pcm: ShortArray, len: Int)

        /** Opcjonalnie: poziom 0..1 (RMS wzmocniony). */
        fun onLevel(level: Float) {}
    }

    const val SAMPLE_RATE = 16000

    /** 1024 próbki = 64 ms — kompromis między opóźnieniem a liczbą wybudzeń CPU. */
    private const val READ_SAMPLES = 1024

    /** Po tylu kolejnych błędach przestajemy walczyć o mikrofon (raport + stop). */
    private const val MAX_FAILURES = 5

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val failures = AtomicInteger(0)

    @Volatile private var consumer: Consumer? = null

    @Volatile private var thread: Thread? = null

    @Volatile private var record: AudioRecord? = null

    @Volatile private var lastError: String? = null

    // ── Usuwanie echa (AEC) ───────────────────────────────────────────────
    // Bez tego mikrofon zbiera głos NIXI z głośnika i wysyła go z powrotem do
    // modelu (fałszywe przerwania, „rozmowa z samą sobą”). Włączamy tylko na
    // czas sesji — nasłuch wake-worda ma zostać surowy.
    @Volatile private var wantEchoCancel = false
    @Volatile private var aec: android.media.audiofx.AcousticEchoCanceler? = null
    @Volatile private var ns: android.media.audiofx.NoiseSuppressor? = null

    /** Czy AEC faktycznie działa na bieżącej sesji AudioRecord. */
    @Volatile var echoCancelActive = false
        private set

    @SuppressLint("MissingPermission")
    fun start(initial: Consumer? = null): Boolean {
        if (initial != null) consumer = initial
        if (!running.compareAndSet(false, true)) return false
        failures.set(0)
        lastError = null
        thread = Thread({ loop() }, "nixi-audio").apply {
            isDaemon = true
            start()
        }
        LogBus.log("audio.start", "AudioBus uruchomiony")
        return true
    }

    fun setConsumer(c: Consumer?) {
        consumer = c
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            // nawet jeśli pętla już nie działa — domknij rekord
            closeRecord()
            return
        }
        consumer = null
        closeRecord()
        thread = null
        LogBus.log("audio.stop", "AudioBus zatrzymany")
    }

    fun isRunning(): Boolean = running.get()

    /**
     * Włącza usuwanie echa na bieżącym rekordzie (i na każdym kolejnym, jeśli
     * AudioRecord zostanie odtworzony po błędzie). Zwraca true, jeśli AEC
     * faktycznie się załączyło — nie każdy telefon/źródło to wspiera.
     */
    fun enableEchoCancel(): Boolean {
        wantEchoCancel = true
        val r = record ?: return false
        return applyEffects(r)
    }

    fun disableEchoCancel() {
        wantEchoCancel = false
        releaseEffects()
    }

    private fun applyEffects(rec: AudioRecord): Boolean = try {
        releaseEffects()
        val session = rec.audioSessionId
        val a = android.media.audiofx.AcousticEchoCanceler.create(session)
        if (a != null) {
            a.enabled = true
            aec = a
        }
        val n = android.media.audiofx.NoiseSuppressor.create(session)
        if (n != null) {
            n.enabled = true
            ns = n
        }
        val ok = a?.enabled == true
        echoCancelActive = ok
        LogBus.log(
            "audio.aec",
            if (ok) "usuwanie echa (AEC) włączone" else "brak AEC — pomaga bramka półduplex",
            if (ok) "ok" else "warn"
        )
        ok
    } catch (t: Throwable) {
        echoCancelActive = false
        LogBus.log("audio.aec", "AEC nie ruszyło: ${t.message}", "warn")
        false
    }

    private fun releaseEffects() {
        runCatching { aec?.let { it.enabled = false; it.release() } }
        runCatching { ns?.let { it.enabled = false; it.release() } }
        aec = null
        ns = null
        echoCancelActive = false
    }

    /** Ostatni błąd mikrofonu (null = wszystko działało). */
    fun lastError(): String? = lastError

    private fun closeRecord() {
        releaseEffects()
        val r = record
        record = null
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun create(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            lastError = "getMinBufferSize=$minBuf"
            return null
        }
        val bufSize = maxOf(minBuf, READ_SAMPLES * 2 * 4)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
        )
        var rec: AudioRecord? = null
        for (src in sources) {
            rec = runCatching {
                AudioRecord(
                    src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize,
                )
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                if (src != MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                    LogBus.log("audio.source", "mikrofon źródło=$src")
                }
                break
            }
            runCatching { rec?.release() }
            rec = null
        }
        if (rec == null) {
            lastError = "AudioRecord nie zainicjalizowany"
            return null
        }
        return try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { rec.release() }
                lastError = "startRecording nie ruszył"
                null
            } else rec
        } catch (t: Throwable) {
            runCatching { rec.release() }
            lastError = t.message ?: "startRecording: wyjątek"
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        var failCount = 0
        try {
            while (running.get()) {
                val rec = create()
                if (rec == null) {
                    failCount++
                    LogBus.log("audio.error", lastError ?: "nieznany błąd mikrofonu", "error")
                    if (!backoff(failCount)) break
                    continue
                }
                record = rec
                // po odtworzeniu rekordu AEC trzeba założyć od nowa
                if (wantEchoCancel) applyEffects(rec)
                val ok = readLoop(rec)
                closeRecord()
                if (!running.get()) break
                if (ok) {
                    failCount = 0
                } else {
                    failCount++
                    LogBus.log("audio.error", "przerwany odczyt mikrofonu ($failCount)", "warn")
                    if (!backoff(failCount)) break
                }
            }
        } catch (t: Throwable) {
            LogBus.logException("audio.loop", t)
            lastError = t.message ?: "wyjątek pętli audio"
        } finally {
            closeRecord()
            running.set(false)
            if (failCount >= MAX_FAILURES) {
                NixiState.emit(
                    NixiState.NixiEvent.ErrorHappened("audio", "mikrofon niedostępny — nasłuch wstrzymany")
                )
            }
        }
    }

    /**
     * Czeka przed kolejną próbą. Zwraca false, gdy nie warto już próbować
     * (koniec działania albo zbyt wiele błędów pod rząd).
     */
    private fun backoff(failCount: Int): Boolean {
        if (failCount >= MAX_FAILURES) {
            LogBus.log("audio.stop", "rezygnuję po $failCount błędach mikrofonu", "error")
            return false
        }
        val inCall = callActive()
        val waitMs = when {
            inCall -> 4000L
            else -> (800L * failCount).coerceAtMost(5000L)
        }
        if (inCall) LogBus.log("audio.pause", "rozmowa telefoniczna — czekam na mikrofon")
        return sleep(waitMs)
    }

    private fun sleep(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (running.get() && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(min(100L, (end - System.currentTimeMillis()).coerceAtLeast(0L)))
            } catch (_: InterruptedException) {
                return false
            }
        }
        return running.get()
    }

    /** Czy trwa połączenie telefoniczne / inny tryb zabierający mikrofon. */
    private fun callActive(): Boolean = try {
        val am = NixiApp.ctx().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
    } catch (_: Throwable) {
        false
    }

    /** @return true gdy pętla skończyła się „normalnie” (stop), false gdy błąd odczytu. */
    private fun readLoop(rec: AudioRecord): Boolean {
        val buf = ShortArray(READ_SAMPLES)
        while (running.get()) {
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (t: Throwable) {
                lastError = t.message ?: "read: wyjątek"
                return false
            }
            if (n > 0) {
                val c = consumer
                if (c != null) {
                    val t0 = System.nanoTime()
                    try {
                        c.onPcm(buf, n)
                    } catch (t: Throwable) {
                        LogBus.logException("audio.consumer", t)
                    }
                    WakeCpuMeter.add(System.nanoTime() - t0)
                    val level = min(1f, rms(buf, n) / 0.02f) // 0.02 RMS ~ głośna mowa
                    try {
                        c.onLevel(level)
                    } catch (t: Throwable) {
                        LogBus.logException("audio.level", t)
                    }
                    NixiState.micLevel.value = level
                }
            } else if (n < 0) {
                // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT / ERROR_BAD_VALUE
                lastError = "read=$n"
                return false
            }
            // n == 0: brak danych (np. chwilowy brak bufora) — kontynuujemy
        }
        return true
    }

    private fun rms(buf: ShortArray, len: Int): Float {
        var sum = 0L
        for (i in 0 until len) {
            val s = buf[i].toInt()
            sum += (s * s).toLong()
        }
        return (sqrt(sum.toDouble() / len) / 32768.0).toFloat()
    }
}

/** Prosty licznik czasu CPU obsługi PCM (do statystyk "Oszczędność"). */
object WakeCpuMeter {
    @Volatile private var accNs = 0L
    @Volatile private var windowStart = System.currentTimeMillis()
    private val lock = Any()

    fun add(ns: Long) {
        synchronized(lock) {
            accNs += ns
            val now = System.currentTimeMillis()
            if (now - windowStart >= 60_000) {
                dev.nixi.store.LocalStore.wakeCpuMsPerMin = accNs / 1_000_000
                accNs = 0
                windowStart = now
            }
        }
    }
}
