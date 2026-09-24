package dev.nixi.wake

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FRONTEND LOG-MEL — ta sama rodzina cech, której używa hotword Google.
 *
 * W pracy „A Cascade Architecture for Keyword Spotting on Mobile Devices"
 * (Gruenstein i in., Google, NIPS 2017) frontend jest opisany jako: sygnał
 * 16 kHz mono → okno 25 ms → widmo mocy → trójkątne filtry mel → logarytm
 * („the log of the triangular mel filters applied to the power spectra"),
 * typowo 32–40 kanałów na klatkę. W tej samej pracy Google podaje rozmiary:
 * tablice wirujące FFT to ~12 kB, a 2 sekundy monofonicznego PCM 16 kHz to
 * 64 kB — dlatego trzymamy dokładnie ten sam podział pracy.
 *
 * Nasza implementacja: preemfaza 0.97, okno Hamminga 25 ms (400 próbek),
 * FFT 512 (zero-padding), 16 trójkątnych filtrów mel (tryb STANDARD) albo
 * 8 (tryb ECO — mniejsza liczba kanałów to mniej mnożeń; w literaturze
 * zejście z 40 na 8 kanałów daje ~6× mniejszy koszt przy ~3,5% względnej
 * straty jakości, stąd taki wybór dla oszczędzania baterii).
 *
 * Klasa jest bezstanowa: klatkę liczymy dla zadanego przesunięcia w buforze,
 * więc detektor sam decyduje, kiedy w ogóle marnować CPU na FFT.
 */
class MelFrontend(
    val sampleRate: Int = 16_000,
    val channels: Int = 16,
) {

    companion object {
        /** 512-punktowa FFT (32 ms @ 16 kHz): okno 25 ms + zera. */
        const val FFT_SIZE = 512

        /** Okno analizy 25 ms @ 16 kHz. */
        const val WINDOW_SAMPLES = 400

        /** Preemfaza — wyrównanie widma, standard w rozpoznawaniu mowy. */
        const val PREEMPHASIS = 0.97f

        private const val LOG_EPS = 1e-6f

        /** Dolna i górna granica pasma filtrów mel. */
        private const val MEL_MIN_HZ = 40.0
        private const val MEL_MAX_HZ = 7_600.0
    }

    private val bins = FFT_SIZE / 2 + 1
    private val win = WINDOW_SAMPLES

    private val hamming = FloatArray(win) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (win - 1))).toFloat()
    }

    /** Trójkątne filtry mel: [kanał][prążek]. */
    private val filters: Array<FloatArray> = buildFilters(channels)

    // ── FFT (radix-2, DIT) ────────────────────────────────────────────────
    private val rev = IntArray(FFT_SIZE) { i ->
        var x = i
        var r = 0
        var bits = 0
        while (bits < 9) { r = (r shl 1) or (x and 1); x = x shr 1; bits++ }
        r
    }
    private val cosT = FloatArray(FFT_SIZE / 2) { k -> cos(2.0 * PI * k / FFT_SIZE).toFloat() }
    private val sinT = FloatArray(FFT_SIZE / 2) { k -> sin(2.0 * PI * k / FFT_SIZE).toFloat() }
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val buf = FloatArray(FFT_SIZE)

    /**
     * Jedna klatka cech: [offset] to początek okna 25 ms w [pcm].
     * Wymaga offset + [WINDOW_SAMPLES] <= pcm.size (wywołujący pilnuje zakresu).
     */
    @Synchronized
    fun computeFrame(pcm: ShortArray, offset: Int): FloatArray {
        // preemfaza + okno Hamminga (poprzednia próbka = kontekst dla preemfazy)
        var prev = pcm[if (offset > 0) offset - 1 else offset].toFloat()
        for (i in 0 until win) {
            val s = pcm[offset + i].toFloat()
            buf[i] = (s - PREEMPHASIS * prev) * hamming[i]
            prev = s
        }
        java.util.Arrays.fill(buf, win, FFT_SIZE, 0f)

        for (i in 0 until FFT_SIZE) {
            re[i] = buf[i]
            im[i] = 0f
        }
        fft()

        val out = FloatArray(channels)
        for (c in 0 until channels) {
            val f = filters[c]
            var acc = 0f
            for (b in 0 until bins) {
                val p = re[b] * re[b] + im[b] * im[b]
                if (f[b] != 0f) acc += f[b] * p
            }
            out[c] = ln(acc + LOG_EPS)
        }
        return out
    }

    private fun fft() {
        for (i in 0 until FFT_SIZE) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= FFT_SIZE) {
            val half = size / 2
            val step = FFT_SIZE / size
            var start = 0
            while (start < FFT_SIZE) {
                var k = 0
                var i = start
                while (i < start + half) {
                    val j = i + half
                    val c = cosT[k]
                    val s = sinT[k]
                    val tre = re[j] * c + im[j] * s
                    val tim = im[j] * c - re[j] * s
                    re[j] = re[i] - tre
                    im[j] = im[i] - tim
                    re[i] += tre
                    im[i] += tim
                    i++
                    k += step
                }
                start += size
            }
            size = size shl 1
        }
    }

    /** Trójkątne filtry na skali mel (formuła HTK: 1127·ln(1+f/700)). */
    private fun buildFilters(count: Int): Array<FloatArray> {
        val melMin = hzToMel(MEL_MIN_HZ)
        val melMax = hzToMel(MEL_MAX_HZ)
        val points = DoubleArray(count + 2) { i ->
            melToHz(melMin + (melMax - melMin) * i / (count + 1))
        }
        return Array(count) { c ->
            val f0 = points[c]
            val f1 = points[c + 1]
            val f2 = points[c + 2]
            FloatArray(bins) { b ->
                val f = b * sampleRate.toDouble() / FFT_SIZE
                val w = when {
                    f <= f0 -> 0.0
                    f < f1 -> (f - f0) / (f1 - f0)
                    f == f1 -> 1.0
                    f < f2 -> (f2 - f) / (f2 - f1)
                    else -> 0.0
                }
                w.toFloat()
            }
        }
    }

    private fun hzToMel(hz: Double): Double = 1127.0 * ln(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (kotlin.math.exp(mel / 1127.0) - 1.0)

}

/**
 * Czyste przekształcenia cech (bez Androida — wszystko da się przetestować).
 */
object Features {

    /** Redukcja liczby kanałów o połowę (16 → 8). */
    fun halve(f: FloatArray): FloatArray {
        val n = f.size / 2
        if (n == 0) return f
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = (f[2 * i] + f[2 * i + 1]) * 0.5f
        return out
    }

    /** Podpróbkowanie w czasie (np. hop 10 ms → 20 ms); uśrednia grupy klatek. */
    fun decimate(frames: List<FloatArray>, factor: Int): List<FloatArray> {
        if (factor <= 1 || frames.isEmpty()) return frames
        val bands = frames[0].size
        val out = ArrayList<FloatArray>(frames.size / factor + 1)
        var i = 0
        while (i + factor <= frames.size) {
            val acc = FloatArray(bands)
            for (j in i until i + factor) {
                val fr = frames[j]
                for (b in 0 until bands) acc[b] += if (b < fr.size) fr[b] else 0f
            }
            val inv = 1f / factor
            for (b in 0 until bands) acc[b] *= inv
            out.add(acc)
            i += factor
        }
        return out
    }

    /**
     * CMVN — normalizacja średniej i wariancji po czasie (per kanał).
     * W praktyce usuwa różnice głośności odtwarzania, odległości od mikrofonu
     * i charakterystyki mikrofonu, czyli dokładnie to, co w pracy Google robi
     * „spectral subtraction" plus normalizacja wejścia sieci.
     */
    fun cmvn(frames: List<FloatArray>): List<FloatArray> {
        if (frames.isEmpty()) return frames
        val bands = frames[0].size
        val mean = FloatArray(bands)
        for (f in frames) for (b in 0 until bands) mean[b] += f[b]
        val inv = 1f / frames.size
        for (b in 0 until bands) mean[b] *= inv

        val vars = FloatArray(bands)
        for (f in frames) {
            for (b in 0 until bands) {
                val d = f[b] - mean[b]
                vars[b] += d * d
            }
        }
        val std = FloatArray(bands) {
            val v = sqrt((vars[it] * inv).toDouble()).toFloat()
            if (v < 1e-3f) 1e-3f else v
        }
        return frames.map { f ->
            FloatArray(bands) { b -> (f[b] - mean[b]) / std[b] }
        }
    }

    /**
     * PODPIS WYPOWIEDZI — długoterminowy kształt widma (LTAS).
     *
     * W kaskadzie Google trzeci filtr to weryfikacja mówcy: osobny model zwraca
     * wektor opisujący głos, a decyzja to podobieństwo kosinusowe. U nas rolę
     * wektora pełni uśredniony profil log-mel wypowiedzi z odjętym poziomem
     * ogólnym, czyli kształt widma, a nie głośność — dokładnie to, co odróżnia
     * mówców i typ mikrofonu. Podobieństwo kosinusowe takiego wektora jest
     * zwykłym współczynnikiem korelacji profili, więc skala nie ma znaczenia.
     */
    fun signature(frames: List<FloatArray>): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)
        val bands = frames[0].size
        val mean = FloatArray(bands)
        var n = 0
        for (f in frames) {
            for (b in 0 until bands) mean[b] += if (b < f.size) f[b] else 0f
            n++
        }
        if (n == 0) return FloatArray(0)
        val inv = 1f / n
        var global = 0f
        for (b in 0 until bands) {
            mean[b] *= inv
            global += mean[b]
        }
        global /= bands
        for (b in 0 until bands) mean[b] -= global
        return mean
    }

    /** Średni podpis z kilku prób (przy rejestracji 3 nagrań). */
    fun meanSignature(list: List<FloatArray>): FloatArray? {
        if (list.isEmpty()) return null
        val size = list[0].size
        if (size == 0) return null
        val out = FloatArray(size)
        var used = 0
        for (s in list) {
            if (s.size != size) continue
            for (i in 0 until size) out[i] += s[i]
            used++
        }
        if (used == 0) return null
        for (i in 0 until size) out[i] /= used
        return out
    }

    /** Podobieństwo kosinusowe dwóch podpisów (1 = identyczne). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na.toDouble()) * sqrt(nb.toDouble())
        if (denom < 1e-9) return 0f
        return (dot / denom).toFloat()
    }
}
