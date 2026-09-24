package dev.nixi.wake

/**
 * Bufor kołowy surowego PCM — odpowiednik „low-power buffer" z kaskady Google.
 *
 * W pracy Google pierwszy stopień „executes continuously, buffering enough
 * audio to safely fit the keyword (typically 2 seconds). Upon detection it
 * passes the audio buffer to the second stage". U nas dokładnie tak samo:
 * w tle robimy tylko tani zapis (memcpy) i tanią energię, a kosztowne FFT
 * liczymy dopiero wtedy, gdy bramka wykryje mowę — i wtedy nadrabiamy klatki
 * z tego bufora, żeby początek frazy nie został obcięty.
 *
 * 2 s × 16 kHz × 2 B = 64 kB — ta sama liczba, którą Google podaje dla DSP.
 */
class PcmRing(val capacity: Int) {

    private val buf = ShortArray(capacity)
    private var head = 0

    /** Liczba próbek, które kiedykolwiek wpłynęły (indeks absolutny). */
    var total = 0L
        private set

    fun write(pcm: ShortArray, len: Int) {
        if (len <= 0) return
        var src = 0
        var remaining = minOf(len, pcm.size)
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - head)
            System.arraycopy(pcm, src, buf, head, chunk)
            head = (head + chunk) % capacity
            src += chunk
            remaining -= chunk
            total += chunk
        }
    }

    /** Najstarszy dostępny indeks absolutny. */
    fun oldest(): Long = if (total > capacity) total - capacity else 0L

    /** Czy zakres [pos, pos+len) jest jeszcze w buforze. */
    fun has(pos: Long, len: Int): Boolean =
        pos >= oldest() && pos >= 0 && pos + len <= total

    /** Kopiuje zakres do [out]; false, gdy dane już wypadły z bufora. */
    fun read(pos: Long, out: ShortArray, len: Int): Boolean {
        if (!has(pos, len)) return false
        var idx = ((pos % capacity).toInt() + capacity) % capacity
        var dst = 0
        var remaining = len
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - idx)
            System.arraycopy(buf, idx, out, dst, chunk)
            idx = (idx + chunk) % capacity
            dst += chunk
            remaining -= chunk
        }
        return true
    }

    fun clear() {
        head = 0
        total = 0
    }
}
