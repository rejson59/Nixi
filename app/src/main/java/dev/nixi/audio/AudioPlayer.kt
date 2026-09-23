package dev.nixi.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.nixi.NixiState
import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Odtwarzacz głosu NIXI (24 kHz mono LINEAR16 — format wyjścia Gemini Live).
 * RMS odtwarzanych danych zasila skalę kuli: głośniej => większa kula.
 *
 * Stabilność: wątek odtwarzania łapie `InterruptedException` (koniec sesji
 * NIE może wywalić procesu) i zawsze zwalnia AudioTrack.
 */
class AudioPlayer {

    private var track: AudioTrack? = null
    private val queue = ArrayBlockingQueue<ByteArray>(64)

    @Volatile private var alive = false

    @Volatile private var worker: Thread? = null

    fun start() {
        if (alive) return
        alive = true
        val minBuf = AudioTrack.getMinBufferSize(
            24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, 96000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            LogBus.log("audio.player", "nie mogę utworzyć AudioTrack: ${t.message}", "error")
            alive = false
            return
        }
        runCatching { t.playbackParams = android.media.PlaybackParams().setSpeed(LocalStore.playRate) }
        runCatching { t.setVolume(LocalStore.outputVolume.coerceIn(0f, 1f)) }
        try {
            t.play()
        } catch (t2: Throwable) {
            LogBus.log("audio.player", "play() nie ruszył: ${t2.message}", "error")
            runCatching { t.release() }
            alive = false
            return
        }
        track = t
        worker = Thread({ loop(t) }, "nixi-player").apply {
            isDaemon = true
            start()
        }
        LogBus.log("audio.player", "start")
    }

    /** Zapis bajtów PCM 24 kHz. Nie blokuje. */
    fun write(bytes: ByteArray) {
        if (!alive || bytes.isEmpty()) return
        // nie pozwól, by kolejka urosła w nieskończoność (np. 429 / zator)
        if (!queue.offer(bytes)) {
            queue.poll()
            queue.offer(bytes)
        }
    }

    /** Przerwanie (barge-in): wyrzuca bufor i ciszy głośnik. */
    fun flush() {
        queue.clear()
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.play() }
        NixiState.speakLevel.value = 0f
    }

    fun stop() {
        if (!alive && track == null) return
        alive = false
        val t = track
        track = null
        // NIE przerywamy wątku: blokujący write() zwolni się po stop()
        runCatching { t?.stop() }
        runCatching { t?.release() }
        worker = null
        queue.clear()
        NixiState.speakLevel.value = 0f
        LogBus.log("audio.player", "stop")
    }

    fun isRunning(): Boolean = alive

    private fun loop(t: AudioTrack) {
        try {
            while (alive) {
                val bytes = try {
                    queue.poll(300, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // gdyby jakikolwiek kod przerwał wątek — kończymy spokojnie
                    return
                } ?: continue
                if (bytes.isEmpty() || !alive) continue
                var off = 0
                while (off < bytes.size && alive) {
                    val written = try {
                        t.write(bytes, off, bytes.size - off)
                    } catch (_: Throwable) {
                        return
                    }
                    if (written > 0) off += written else break
                }
                NixiState.speakLevel.value = rmsLevel(bytes)
            }
        } catch (t2: Throwable) {
            LogBus.log("audio.player", "pętla odtwarzania: ${t2.message}", "warn")
        } finally {
            if (alive) {
                // wątek wyszedł sam — oznacz stan, żeby nikt nie czekał na dźwięk
                alive = false
            }
            NixiState.speakLevel.value = 0f
        }
    }

    @SuppressLint("WrongConstant")
    private fun rmsLevel(bytes: ByteArray): Float {
        var sum = 0L
        var i = 0
        while (i + 1 < bytes.size) {
            val s = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
            sum += (s * s).toLong()
            i += 2
        }
        val count = (bytes.size / 2).coerceAtLeast(1)
        return (sqrt(sum.toDouble() / count) / 32768.0 * 5.0).toFloat().coerceIn(0f, 1f)
    }
}
