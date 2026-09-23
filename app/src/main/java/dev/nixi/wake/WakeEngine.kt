package dev.nixi.wake

import dev.nixi.util.LogBus
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.max

/**
 * WYKRYWANIE "Hej Nixi" — CAŁKOWICIE OFFLINE, bez modeli ML i bez sieci.
 *
 * Zasada (klasyczny porównywacz cech spektrogramowych):
 *  1. PCM 16 kHz -> klatki 20 ms (10/20 ms hop zależnie od trybu),
 *  2. 16 pasm mel z przetwornicą Goertzel (tanio: ~16 * 320 mnożeń/klatkę),
 *  3. adaptacyjna "podłoga" szumu per pasmo -> cecha = log-wzrost nad podłogą,
 *  4. bramka energii (czy w ogóle padła mowa),
 *  5. DTW (Sakoe-Chiba band) okna bufora przeciwko szablonowi "Hej Nixi"
 *     zarejestrowanemu przez użytkownika podczas konfiguracji (3 próby).
 *
 * Koszt CPU: w ciszy tylko krok 1-3 (kilka µs). DTW rusza wyłącznie, gdy
 * bramka wykrywa mowę — stąd niska cena w tle.
 */
class WakeEngine {

    enum class Mode { STANDARD, ECO }

    var mode = Mode.STANDARD
    var sensitivity = 0.5f

    val bands: Int get() = if (mode == Mode.STANDARD) 16 else 8
    val hopMs: Int get() = if (mode == Mode.STANDARD) 10 else 20

    private val frameSamples = 320           // 20 ms @ 16 kHz
    private val maxFrames = 220              // ~2.2 s bufora

    private var templates: List<List<FloatArray>> = emptyList()
    private var baseThreshold = 0.45

    private val ring = ArrayDeque<FloatArray>()
    private val floor = FloatArray(16)
    private var floorReady = false

    private var sampleAccum = ShortArray(2048)
    private var samplePos = 0

    private var lastHitAt = 0L
    private val cooldownMs = 12_000L

    private var gateFrames = 0               // ile z rzędu klatek "mowa"
    private var evalCounter = 0

    // pasma mel: 120 Hz .. 6.4 kHz
    private val freqs = FloatArray(16) { i ->
        val m = 179f + i * (2427f - 179f) / 15f
        1333.33f * (10f.pow(m / 2595f) - 1f)
    }

    fun configure(mode: Mode, sensitivity: Float) {
        this.mode = mode
        this.sensitivity = sensitivity
        ring.clear()
        floor.fill(0f)
        floorReady = false
        gateFrames = 0
    }

    // ── Szablony ──────────────────────────────────────────────────────────

    /** JSON: {"bands":n,"hopMs":h,"threshold":t,"templates":[[[..],[..]],...]} */
    fun loadFromJson(json: String) {
        try {
            val o = JSONObject(json)
            val b = o.optInt("bands", 16)
            val h = o.optInt("hopMs", 10)
            baseThreshold = o.optDouble("threshold", 0.45)
            val arr = o.getJSONArray("templates")
            val list = ArrayList<List<FloatArray>>(arr.length())
            for (i in 0 until arr.length()) {
                val framesArr = arr.getJSONArray(i)
                val frames = ArrayList<FloatArray>(framesArr.length())
                for (j in 0 until framesArr.length()) {
                    val f = framesArr.getJSONArray(j)
                    val vec = FloatArray(min(b, 16))
                    for (k in vec.indices) vec[k] = f.optDouble(k, 0.0).toFloat()
                    frames.add(vec)
                }
                if (frames.isNotEmpty()) list.add(frames)
            }
            templates = list
            mode = if (h <= 10) Mode.STANDARD else Mode.ECO
            LogBus.log("wake.templates", "wczytano ${list.size} szablonów (b=$b hop=${h}ms)")
        } catch (t: Throwable) {
            LogBus.log("wake.templates", "błąd wczytywania: ${t.message}", "warn")
        }
    }

    /** Zwraca JSON do zapisu w LocalStore. */
    fun toJson(bandsUsed: Int, hopUsed: Int, threshold: Double, templates: List<List<FloatArray>>): String {
        val o = JSONObject()
        o.put("bands", bandsUsed)
        o.put("hopMs", hopUsed)
        o.put("threshold", threshold)
        val arr = JSONArray()
        for (t in templates) {
            val framesArr = JSONArray()
            for (f in t) {
                val fArr = JSONArray()
                f.forEach { fArr.put(it) }
                framesArr.put(fArr)
            }
            arr.put(framesArr)
        }
        o.put("templates", arr)
        return o.toString()
    }

    fun hasTemplates(): Boolean = templates.isNotEmpty()

    // ── Wejście PCM ───────────────────────────────────────────────────────

    /** Zwraca true, gdy wykryto "Hej Nixi". */
    fun onPcm(pcm: ShortArray, len: Int): Boolean {
        // docelowa liczba pasm = bands (16 lub 8)
        var i = 0
        while (i < len) {
            val take = min(len - i, frameSamples - samplePos)
            System.arraycopy(pcm, i, sampleAccum, samplePos, take)
            i += take
            samplePos += take
            if (samplePos >= frameSamples) {
                samplePos = 0
                if (processFrame()) return true
            }
        }
        return false
    }

    private fun processFrame(): Boolean {
        val raw = FloatArray(bands)
        for (b in 0 until bands) {
            raw[b] = goertzelPower(sampleAccum, frameSamples, freqs[b])
        }

        // adaptacyjna podłoga szumu (pierwsza klatka = kalibracja)
        if (!floorReady) {
            for (b in 0 until bands) floor[b] = raw[b]
            floorReady = true
        } else {
            for (b in 0 until bands) {
                val alpha = if (raw[b] > floor[b]) 0.25f else 0.02f
                floor[b] += alpha * (raw[b] - floor[b])
            }
        }

        val feat = FloatArray(bands)
        var gateSum = 0f
        for (b in 0 until bands) {
            val f = ((ln(1f + raw[b]) - ln(1f + floor[b])) / 3f).coerceIn(0f, 4f)
            feat[b] = f
            gateSum += f
        }
        val gate = gateSum / bands > 0.28f
        gateFrames = if (gate) gateFrames + 1 else 0

        ring.addLast(feat)
        while (ring.size > maxFrames) ring.removeFirst()

        // ocena DTW tylko gdy była mowa + throttling (co 3. klatkę)
        evalCounter++
        if (gateFrames >= 8 && evalCounter % 3 == 0 &&
            System.currentTimeMillis() - lastHitAt > cooldownMs
        ) {
            evalCounter = 0
            val dist = bestDistance()
            if (dist < effectiveThreshold()) {
                lastHitAt = System.currentTimeMillis()
                gateFrames = 0
                ring.clear()
                return true
            }
        }
        return false
    }

    private fun effectiveThreshold(): Double {
        // wrażliwość 0 => surowo (x0.85), 1 => czuło (x1.35)
        return baseThreshold * (0.85 + sensitivity * 0.5)
    }

    /** Najlepsza (najniższa) odległość DTW okna bufora do dowolnego szablonu. */
    private fun bestDistance(): Double {
        if (templates.isEmpty() || ring.size < 30) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (t in templates) {
            val lt = t.size
            // okna o długości 0.75..1.45 * długości szablonu, zawsze kończące się "teraz"
            var len = (lt * 0.75f).toInt()
            while (len <= (lt * 1.45f).toInt() && len < ring.size) {
                val start = ring.size - len
                val window = ArrayList<FloatArray>(len)
                var k = start
                while (k < ring.size) {
                    window.add(ring[k].copyOf(bands))
                    k++
                }
                val d = dtw(window, t)
                if (d < best) best = d
                len += 3
            }
        }
        return best
    }

    // ── Goertzel ──────────────────────────────────────────────────────────

    private fun goertzelPower(x: ShortArray, n: Int, freq: Float): Float {
        val w = 2.0 * Math.PI * freq / 16000.0
        val coeff = 2.0 * cos(w)
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            s0 = x[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return (s1 * s1 + s2 * s2 - coeff * s1 * s2).coerceAtLeast(0.0).toFloat() / (n * n)
    }

    // ── DTW (Sakoe-Chiba) ─────────────────────────────────────────────────

    internal fun dtw(a: List<FloatArray>, b: List<FloatArray>): Double {
        val la = a.size
        val lb = b.size
        if (la == 0 || lb == 0) return 10.0
        val band = max(6, (0.35 * max(la, lb)).toInt())
        val inf = Double.MAX_VALUE / 2
        var prev = DoubleArray(lb + 1) { inf }
        var curr = DoubleArray(lb + 1) { inf }
        prev[0] = 0.0
        for (i in 1..la) {
            curr.fill(inf)
            val lo = max(1, i - band)
            val hi = min(lb, i + band)
            val ai = a[i - 1]
            for (j in lo..hi) {
                val d = frameDist(ai, b[j - 1])
                val m = min(prev[j], min(curr[j - 1], prev[j - 1]))
                curr[j] = d + m
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        val dist = prev[lb]
        if (dist >= inf) return 10.0
        return dist / (la + lb)
    }

    private fun frameDist(x: FloatArray, y: FloatArray): Double {
        val n = min(x.size, y.size)
        var s = 0.0
        for (i in 0 until n) {
            val d = (x[i] - y[i]).toDouble()
            s += d * d
        }
        return s / n
    }
}

/**
 * Rejestrowanie szablonu "Hej Nixi" (3 próby) — logika wspólna dla
 * onboardingu i ustawień.
 */
object WakeEnroll {

    data class AttemptResult(val frames: List<FloatArray>, val ok: Boolean, val note: String)

    /**
     * Przetwarza nagraną próbkę (krótkie nagranie PCM 16 kHz, np. 1.5-2.5 s)
     * i zwraca obcięte cechy mowy.
     */
    fun processAttempt(pcm: ShortArray, pcmLen: Int, bands: Int = 16): AttemptResult {
        // klatkuj ręcznie (null = cisza)
        val frames = ArrayList<FloatArray?>(pcmLen / 320)
        val freqs = FloatArray(16) { i ->
            val m = 179f + i * (2427f - 179f) / 15f
            1333.33f * (10f.pow(m / 2595f) - 1f)
        }
        var pos = 0
        val frame = ShortArray(320)
        // podłoga z ciszy na początku
        val floor = FloatArray(bands)
        var floored = false
        while (pos + 320 <= pcmLen) {
            for (k in 0 until 320) frame[k] = pcm[pos + k]
            val raw = FloatArray(bands)
            for (b in 0 until bands) raw[b] = goertzel(frame, 320, freqs[b])
            if (!floored) {
                for (b in 0 until bands) floor[b] = raw[b]
                floored = true
                pos += 320
                continue
            }
            for (b in 0 until bands) {
                val alpha = if (raw[b] > floor[b]) 0.25f else 0.02f
                floor[b] += alpha * (raw[b] - floor[b])
            }
            val feat = FloatArray(bands)
            var gate = 0f
            for (b in 0 until bands) {
                val f = ((ln(1f + raw[b]) - ln(1f + floor[b])) / 3f).coerceIn(0f, 4f)
                feat[b] = f
                gate += f
            }
            frames.add(if (gate / bands > 0.28f) feat else null)
            pos += 320
        }
        // obetnij ciszę na krańcach
        val nonNull = frames.map { if (it == null) -1 else 0 }
        val first = nonNull.indexOf(0)
        val last = nonNull.lastIndexOf(0)
        if (first < 0) return AttemptResult(emptyList(), false, "nie wykryto mowy — spróbuj głośniej")
        val kept = (max(0, first - 4)..min(frames.size - 1, last + 4))
            .mapNotNull { frames[it] }
        if (kept.size < 20) return AttemptResult(emptyList(), false, "zbyt krótkie nagranie — powiedz „Hej Nixi”")
        return AttemptResult(kept, true, "ok (${kept.size} klatek)")
    }

    /** Progi + odległości wzajemne (kalibracja). */
    fun calibrate(templates: List<List<FloatArray>>): Double {
        if (templates.size < 2) return 0.45
        val engine = WakeEngine()
        var sum = 0.0
        var n = 0
        for (i in templates.indices) {
            for (j in i + 1 until templates.size) {
                sum += engine.dtw(templates[i], templates[j])
                n++
            }
        }
        val mean = if (n > 0) sum / n else 0.3
        return (mean * 1.6 + 0.12).coerceIn(0.25, 0.8)
    }

    private fun goertzel(x: ShortArray, n: Int, freq: Float): Float {
        val w = 2.0 * Math.PI * freq / 16000.0
        val coeff = 2.0 * cos(w)
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            s0 = x[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return (s1 * s1 + s2 * s2 - coeff * s1 * s2).coerceAtLeast(0.0).toFloat() / (n * n)
    }
}

private fun Float.pow(e: Float): Float {
    return Math.pow(this.toDouble(), e.toDouble()).toFloat()
}
