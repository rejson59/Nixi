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
 *
 * Liczymy OBIE strony (wejście + wyjście), bo w Live API do limitu TPM
 * wlicza się też to, co model już wygenerował — bez tego zbijaliśmy limit
 * szybciej, niż chronometr pokazywał.
 *
 * Testowalne: limit pochodzi z [limitProvider] (domyślnie z ustawień).
 */
class TpmGuard(private val limitProvider: () -> Int = { fromStore() }) {

    companion object {
        const val TOKENS_PER_SEC_AUDIO_IN = 25
        const val TOKENS_PER_SEC_AUDIO_OUT = 25
        const val WINDOW_MS = 60_000L

        const val LIMIT_ECO = 45_000
        const val LIMIT_STANDARD = 55_000
        const val LIMIT_MAX = 65_000

        fun fromStore(): Int = when (LocalStore.tpmMode) {
            "eco" -> LIMIT_ECO
            "custom" -> LocalStore.tpmCustomLimit.coerceIn(10_000, LIMIT_MAX)
            "off" -> Int.MAX_VALUE
            else -> LIMIT_STANDARD
        }

        fun tokensForAudioSeconds(sec: Double): Int =
            (sec * TOKENS_PER_SEC_AUDIO_IN).toInt().coerceAtLeast(1)

        fun tokensForOutputSeconds(sec: Double): Int =
            (sec * TOKENS_PER_SEC_AUDIO_OUT).toInt().coerceAtLeast(1)

        fun tokensForText(text: String): Int = text.length / 4 + 1

        fun tokensForImage(widthPx: Int, heightPx: Int): Int =
            (widthPx * heightPx / 750).coerceAtLeast(50)
    }

    private val lock = ReentrantLock()
    private val events = ArrayDeque<Pair<Long, Int>>()
    private var backoffUntil = 0L

    fun limitTokens(): Int = limitProvider()

    /** true = wolno wysłać; false = chwilowa pauza (okno za małe). */
    fun canSend(tokens: Int): Boolean = lock.withLock {
        prune()
        if (System.currentTimeMillis() < backoffUntil) return false
        val limit = limitTokens()
        if (limit == Int.MAX_VALUE) return true
        val used = events.sumOf { it.second }
        used + tokens <= limit
    }

    fun addUsed(tokens: Int) = lock.withLock {
        if (tokens <= 0) return
        prune()
        events.addLast(System.currentTimeMillis() to tokens)
    }

    fun noteRateLimit(backoffMs: Long = 60_000L) = lock.withLock {
        backoffUntil = System.currentTimeMillis() + backoffMs
        LogBus.log("tpm.429", "backoff ${backoffMs / 1000} s", "warn")
    }

    /** Sekundy do końca backoffu (0 = brak). */
    fun backoffSec(): Long = lock.withLock {
        ((backoffUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    }

    data class Snapshot(val used: Int, val limit: Int, val backoffSec: Long) {
        val percent: Int get() = if (limit <= 0 || limit == Int.MAX_VALUE) 0 else (used * 100 / limit)
    }

    fun snapshot(): Snapshot = lock.withLock {
        prune()
        val limit = limitTokens()
        Snapshot(
            used = events.sumOf { it.second },
            limit = if (limit == Int.MAX_VALUE) LIMIT_MAX else limit,
            backoffSec = ((backoffUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0),
        )
    }

    fun reset() = lock.withLock {
        events.clear()
        backoffUntil = 0
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        while (events.isNotEmpty() && events.first().first < cutoff) events.removeFirst()
    }
}
