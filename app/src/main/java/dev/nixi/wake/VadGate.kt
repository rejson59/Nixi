package dev.nixi.wake

import kotlin.math.sqrt

/**
 * BRAMKA MOWY (VAD) — tani pierwszy filtr, który decyduje, czy w ogóle warto
 * liczyć FFT.
 *
 * W kaskadzie Google pierwszy stopień działa ciągle, ale robi to na DSP,
 * gdzie „koszt" to mikrowaty. W aplikacji na telefonie odpowiednikiem DSP jest
 * to, że w ciszy nie liczymy nic poza energią sygnału (kilkaset mnożeń na
 * klatkę), a pełny frontend mel włącza się dopiero na mowę.
 *
 * Detale, które mają znaczenie w praktyce:
 *  - adaptacyjna podłoga szumu (uczy się otoczenia: cichy pokój vs. autobus),
 *  - histereza: inne progi otwarcia i zamknięcia, żeby bramka nie „mrugała"
 *    na końcówkach sylab,
 *  - rozgrzewka: przez pierwsze ~200 ms nic nie robimy (podłoga musi się
 *    ustalić), więc start nasłuchu nie może dać fałszywego trafienia,
 *  - podłoga jest aktualizowana wyłącznie w ciszy — inaczej mowa
 *    „podnosiłaby" sobie próg i bramka nigdy by się nie otworzyła.
 */
class VadGate(
    private val hopMs: Int = 10,
    private val openOffsetDb: Float = 9f,
    private val closeOffsetDb: Float = 4f,
    private val openFrames: Int = 2,
    private val closeMs: Int = 300,
    private val warmupFrames: Int = 20,
) {

    /** Aktualna podłoga szumu w dB. */
    var floorDb: Float = -70f
        private set

    var open: Boolean = false
        private set

    /** True tylko na klatce, w której bramka się właśnie otworzyła. */
    var justOpened: Boolean = false
        private set

    private var initialized = false
    private var warmup = 0
    private var loudRun = 0
    private var quietRun = 0

    /** Kwadratowa energia ramki → poziom w dBFS (bez alokacji). */
    fun levelDb(pcm: ShortArray, offset: Int, len: Int): Float {
        var sum = 0.0
        var i = offset
        val end = offset + len
        while (i < end) {
            val s = pcm[i].toDouble()
            sum += s * s
            i++
        }
        val n = if (len > 0) len else 1
        val rms = sqrt(sum / n)
        val db = 20.0 * kotlin.math.log10(rms / 32768.0 + 1e-9)
        return db.toFloat()
    }

    /** Zwraca stan bramki po tej ramce. Ustawia [justOpened]. */
    fun update(db: Float): Boolean {
        justOpened = false

        if (!initialized) {
            floorDb = db
            initialized = true
            warmup = warmupFrames
            return false
        }
        if (warmup > 0) {
            // rozgrzewka: podłoga = minimum z dotychczasowych ramek
            if (db < floorDb) floorDb = db
            warmup--
            return false
        }

        val openThr = floorDb + openOffsetDb
        val closeThr = floorDb + closeOffsetDb

        if (db > openThr) {
            loudRun++
            quietRun = 0
        } else {
            quietRun++
            loudRun = 0
        }

        if (!open) {
            if (loudRun >= openFrames) {
                open = true
                justOpened = true
            }
        } else if (db < closeThr && quietRun * hopMs >= closeMs) {
            open = false
        }

        if (!open) {
            // w ciszy uczymy się podłogi; przy głośnym tle — bardzo powoli
            val alpha = if (db < floorDb + closeOffsetDb) 0.05f else 0.004f
            floorDb += alpha * (db - floorDb)
        }
        return open
    }

    fun reset() {
        initialized = false
        warmup = 0
        loudRun = 0
        quietRun = 0
        open = false
        justOpened = false
        floorDb = -70f
    }
}

/** Podłoga szumu per kanał mel (odpowiednik „spectral subtraction" z pracy). */
internal class SpectralFloor(private val bands: Int) {

    private val floor = FloatArray(bands)
    private var ready = false

    /** Uczy się tła wyłącznie z ramek, w których nie ma mowy. */
    fun observe(frame: FloatArray) {
        if (frame.size < bands) return
        if (!ready) {
            System.arraycopy(frame, 0, floor, 0, bands)
            ready = true
            return
        }
        for (b in 0 until bands) floor[b] += 0.05f * (frame[b] - floor[b])
    }

    /** Cecha = log-energia ponad tło (nigdy poniżej zera). */
    fun subtract(frame: FloatArray): FloatArray {
        val out = FloatArray(bands)
        if (!ready) {
            for (b in 0 until bands) out[b] = frame[b]
            return out
        }
        for (b in 0 until bands) {
            val v = frame[b] - floor[b]
            out[b] = if (v > 0f) v else 0f
        }
        return out
    }

    val isReady: Boolean get() = ready

    fun reset() {
        ready = false
        java.util.Arrays.fill(floor, 0f)
    }

    /** Średni poziom tła (diagnostyka). */
    fun average(): Float {
        var s = 0f
        for (v in floor) s += v
        return if (bands > 0) s / bands else 0f
    }
}
