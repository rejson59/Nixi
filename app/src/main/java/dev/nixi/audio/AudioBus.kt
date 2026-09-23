package dev.nixi.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.nixi.NixiState
import dev.nixi.util.LogBus
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt

/**
 * JEDEN wspólny rekord (16 kHz, mono, 16-bit) dla całej aplikacji.
 * Nasłuch wake-word i sesja głosowa przełączają [Consumer], dzięki czemu
 * telefon ma cały czas otwarty tylko jeden strumień mikrofonu (niskie
 * zużycie baterii — wątek blokuje się w read(), CPU obudza się ~8x/s).
 */
object AudioBus {

    interface Consumer {
        /** Surowe PCM 16 kHz mono (len < pcm.size). */
        fun onPcm(pcm: ShortArray, len: Int)
        /** Opcjonalnie: poziom 0..1 (RMS wzmocniony). */
        fun onLevel(level: Float) {}
    }

    const val SAMPLE_RATE = 16000

    private val running = AtomicBoolean(false)
    @Volatile private var consumer: Consumer? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(initial: Consumer? = null): Boolean {
        if (running.compareAndSet(false, true)) {
            consumer = initial
            thread = Thread({ loop() }, "nixi-audio").apply { start() }
            LogBus.log("audio.start", "AudioBus uruchomiony")
            return true
        }
        return false
    }

    fun setConsumer(c: Consumer?) {
        consumer = c
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            consumer = null
            thread?.interrupt()
            thread = null
            val r = record
            record = null
            try {
                r?.stop()
                r?.release()
            } catch (_: Exception) {
            }
            LogBus.log("audio.stop", "AudioBus zatrzymany")
        }
    }

    fun isRunning(): Boolean = running.get()

    @SuppressLint("MissingPermission")
    private fun loop() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, SAMPLE_RATE / 4)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                LogBus.log("audio.error", "AudioRecord nie zainicjalizowany", "error")
                rec.release()
                running.set(false)
                return
            }
            record = rec
            rec.startRecording()

            val buf = ShortArray(2048)
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val c = consumer
                    if (c != null) {
                        val t0 = System.nanoTime()
                        try {
                            c.onPcm(buf, n)
                        } catch (t: Throwable) {
                            LogBus.logException("audio.consumer", t)
                        }
                        val cpuNs = System.nanoTime() - t0
                        WakeCpuMeter.add(cpuNs)
                        val rms = rms(buf, n)
                        val level = min(1f, (rms / 0.02f)) // 0.02 RMS ~ głośna mowa
                        c.onLevel(level)
                        NixiState.micLevel.value = level
                    }
                } else if (n < 0) {
                    LogBus.log("audio.error", "read: $n", "error")
                    break
                }
            }
            try {
                rec.stop()
            } catch (_: Exception) {
            }
        } catch (t: Throwable) {
            LogBus.logException("audio.loop", t)
        } finally {
            val r = record
            record = null
            try {
                r?.stop()
                r?.release()
            } catch (_: Exception) {
            }
            running.set(false)
        }
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
                val ms = accNs / 1_000_000
                dev.nixi.store.LocalStore.wakeCpuMsPerMin = ms
                accNs = 0
                windowStart = now
            }
        }
    }
}
