package dev.nixi.live

import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * KLIENCKA OCHRONA LIMITU TPM (Gemini free tier: 65K TPM).
 *
 * NIGDY nie wysyłamy audio, gdy pusta część okna 60 s nie mieści szacunku
 * kolejnego chunka. Przy 429 od Google stosujemy backoff 60 s i zamykamy
 * sesję z powiadomieniem.
 *
 * Szacunki tokenów (dokumentowane założenia):
 *  - audio wejście : 25 tokenów/s
 *  - audio wyjście : 25 tokenów/s
 *  - tekst         : 1 token / 4 znaki
 *  - obraz         : piksele / 750
 */
class TpmGuard {

    companion object {
        const val TOKENS_PER_SEC_AUDIO_IN = 25
        const val TOKENS_PER_SEC_AUDIO_OUT = 25
        const val WINDOW_MS = 60_000L

        fun tokensForAudioSeconds(sec: Double): Int =
            (sec * TOKENS_PER_SEC_AUDIO_IN).toInt().coerceAtLeast(1)

        fun tokensForText(text: String): Int = text.length / 4 + 1

        fun tokensForImage(widthPx: Int, heightPx: Int): Int =
            (widthPx * heightPx / 750).coerceAtLeast(50)
    }

    private val lock = ReentrantLock()
    private val events = ArrayDeque<Pair<Long, Int>>()
    private var backoffUntil = 0L

    fun limitTokens(): Int = when (LocalStore.tpmMode) {
        "eco" -> 45_000
        "custom" -> LocalStore.tpmCustomLimit.coerceIn(10_000, 65_000)
        "off" -> Int.MAX_VALUE
        else -> 55_000
    }

    /** true = wolno wysłać; false = chwilowa pauza (okno za małe). */
    fun canSend(tokens: Int): Boolean = lock.withLock {
        prune()
        if (System.currentTimeMillis() < backoffUntil) return false
        val used = events.sumOf { it.second }
        used + tokens <= limitTokens()
    }

    fun addUsed(tokens: Int) = lock.withLock {
        prune()
        events.addLast(System.currentTimeMillis() to tokens)
    }

    fun noteRateLimit() = lock.withLock {
        backoffUntil = System.currentTimeMillis() + 60_000
        LogBus.log("tpm.429", "backoff 60 s", "warn")
    }

    data class Snapshot(val used: Int, val limit: Int, val backoffSec: Long)

    fun snapshot(): Snapshot = lock.withLock {
        prune()
        Snapshot(
            used = events.sumOf { it.second },
            limit = if (limitTokens() == Int.MAX_VALUE) 65_000 else limitTokens(),
            backoffSec = ((backoffUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0),
        )
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        while (events.isNotEmpty() && events.first().first < cutoff) events.removeFirst()
    }
}
