package dev.nixi.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
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
 */
class AudioPlayer {

    private var track: AudioTrack? = null
    private val queue = ArrayBlockingQueue<ByteArray>(128)
    @Volatile private var alive = false
    private var worker: Thread? = null

    fun start() {
        if (alive) return
        alive = true
        val minBuf = AudioTrack.getMinBufferSize(
            24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
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
        try {
            t.setSpeed(LocalStore.playRate)
        } catch (_: Exception) {
        }
        t.setVolume(LocalStore.outputVolume)
        t.play()
        track = t
        worker = Thread({ loop(t) }, "nixi-player").apply { start() }
        LogBus.log("audio.player", "start")
    }

    /** Zapis brazy PCM 24 kHz (base64 -> bajty). Nie blokuje. */
    fun write(bytes: ByteArray) {
        if (alive) {
            // nie pozwól, by kolejka urosła w nieskończoność (np. 429 / zator)
            if (!queue.offer(bytes)) {
                runCatching { queue.poll(10, TimeUnit.MILLISECONDS) }
                queue.offer(bytes)
            }
        }
    }

    /** Przerwanie (barge-in): wyrzuca bufor i ciszy głośnik. */
    fun flush() {
        queue.clear()
        try {
            track?.flush()
        } catch (_: Exception) {
        }
        NixiState.speakLevel.value = 0f
    }

    fun stop() {
        alive = false
        worker?.interrupt()
        worker = null
        val t = track
        track = null
        runCatching { t?.stop() }
        runCatching { t?.release() }
        NixiState.speakLevel.value = 0f
        LogBus.log("audio.player", "stop")
    }

    @SuppressLint("WrongConstant")
    private fun loop(t: AudioTrack) {
        while (alive) {
            val bytes = queue.poll(300, TimeUnit.MILLISECONDS) ?: continue
            if (bytes.isEmpty()) continue
            // zapisz w pętli, bo write() może wrócić wcześniej (stare API)
            var off = 0
            while (off < bytes.size && alive) {
                val written = t.write(bytes, off, bytes.size - off)
                if (written > 0) off += written
            }
            val level = rmsLevel(bytes)
            NixiState.speakLevel.value = level
        }
    }

    private fun rmsLevel(bytes: ByteArray): Float {
        var sum = 0L
        var i = 0
        while (i + 1 < bytes.size) {
            val s = (bytes[i].toInt() or (bytes[i + 1].toInt() shl 8))
            sum += (s * s).toLong()
            i += 2
        }
        val count = (bytes.size / 2).coerceAtLeast(1)
        return sqrt(sum / count) / 32768f * 5f
    }
}
