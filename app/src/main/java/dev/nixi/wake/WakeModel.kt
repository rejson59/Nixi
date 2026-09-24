package dev.nixi.wake

import org.json.JSONArray
import org.json.JSONObject

/**
 * MODEL FRAZY KLUCZOWEJ: szablony z rejestracji (3 próby) + progi + podpis
 * mówcy + negatywy (nauczone fałszywe trafienia).
 *
 * Skąd taki podział — z pracy Google o kaskadzie (Gruenstein i in., 2017):
 *  - drugi stopień dostaje bufor audio i podejmuje decyzję większym,
 *    dokładniejszym modelem — u nas jest to DTW na pełnej rozdzielczości cech,
 *  - „speaker verification acts as a third filter… can reduce the overall FAR
 *    by a factor of 5 to 10 while adding less than 1% absolute additional FRR",
 *    realizowany przez porównanie podpisu widmowego (mean/std kanałów) —
 *    odpowiednik podpisu d-vector, tylko liczony klasycznie,
 *  - progi wyznaczamy z danych użytkownika (rozrzut między jego własnymi
 *    próbami), więc skala cech nie ma znaczenia i nie trzeba nic zgadywać.
 *
 * Wszystko działa lokalnie: żadne audio ani cechy nie opuszczają telefonu.
 */
internal class WakeModel {

    companion object {
        /** Format zapisu szablonów; 1 = stary detektor Goertzel (niekompatybilny). */
        const val FORMAT_VERSION = 2

        /** Maksymalna liczba trzymanych negatywów (in-memory, bez zapisu). */
        const val MAX_NEGATIVES = 8

        /**
         * Dolny limit „rozrzutu" między próbami. Bez tego przy bardzo
         * powtarzalnych nagraniach próg zszedłby do zera i nic by nie
         * pasowało. Skala odniesienia: dwa niezależne fragmenty mowy mają
         * odległość ~2, więc 0.3 to wciąż ostro.
         */
        const val MIN_SPREAD_S2 = 0.30
        const val MIN_SPREAD_S1 = 0.20

        /**
         * Górne limity rozrzutu. Bez nich ktoś, kto nagrał trzy bardzo różne
         * próby, dostałby tak luźny próg, że detektor reagowałby na wszystko.
         * Skala: cechy są z-normalizowane po CMVN, więc dwa NIEZWIĄZANE
         * fragmenty mowy mają odległość ~2 na klatkę; próg 0,74 (limit × 1,35
         * × 1,1) wciąż wymaga, by średnie niedopasowanie było ponad dwa razy
         * mniejsze niż dla losowej mowy.
         */
        const val MAX_SPREAD_S2 = 0.50
        const val MAX_SPREAD_S1 = 0.45

        /** Jak luźny jest pierwszy stopień (ma nie przegapić frazy). */
        const val S1_FACTOR = 2.4

        /** Jak ostry jest drugi stopień (ma nie wpuszczać śmieci). */
        const val S2_FACTOR = 1.35
    }

    /** Kanały w zapisanych szablonach (kanoniczne: 16, hop 10 ms). */
    var canonicalBands = 16
        private set

    /** Mnożnik progu (1.0 = tak jak policzone z rejestracji). */
    var thresholdFactor = 1.0
        private set

    /** Próg podobieństwa podpisu (trzeci filtr). */
    var sigThreshold = 0.60f
        private set

    /** Podpis mówcy z rejestracji (mean+std kanałów, bez CMVN). */
    var enrolledSig: FloatArray? = null
        private set

    /** Szablony w postaci kanonicznej (16 kanałów, hop 10 ms). */
    var rawTemplates: List<List<FloatArray>> = emptyList()
        private set

    // ── Widoki pochodne, liczone przy [derive] ────────────────────────────

    /** Stage 2: kanały aktywnego trybu + CMVN. */
    var stage2Templates: List<List<FloatArray>> = emptyList()
        private set

    /** Stage 1: 8 kanałów, bez średniej ramki, podpróbkowane w czasie. */
    var stage1Template: List<FloatArray> = emptyList()
        private set

    /** Rozrzut między próbami użytkownika — podstawa progów (patrz niżej). */
    var spreadStage1 = MIN_SPREAD_S1
        private set
    var spreadStage2 = MIN_SPREAD_S2
        private set

    /** Ile razy detektor odrzucił kandydata po podpisie/negatywach (statystyki). */
    private val negatives = ArrayDeque<FloatArray>()

    fun hasTemplates(): Boolean = rawTemplates.isNotEmpty()

    /**
     * Wstawia szablony bezpośrednio (bez JSON-a).
     * Ścieżka używana po rejestracji: zapisujemy JSON do preferencji, ale
     * silnikowi przekazujemy cechy wprost — mniej okazji do rozjechania się
     * formatu, a testy nie muszą dotykać org.json (na JVM to zaślepka).
     */
    fun setTemplates(templates: List<List<FloatArray>>, signature: FloatArray?, sigThreshold: Float) {
        rawTemplates = templates.filter { it.isNotEmpty() }
        enrolledSig = signature
        this.sigThreshold = sigThreshold
        clearNegatives()
    }

    fun negativeCount(): Int = negatives.size

    // ── Ładowanie / zapis ─────────────────────────────────────────────────

    /**
     * Zwraca true, gdy wczytano szablon w nowym formacie.
     * Stary format (v1, cechy Goertzla) jest niekompatybilny z nowym
     * frontendem mel — wtedy czyścimy model i wołający prosi o rejestrację.
     */
    fun loadFromJson(json: String): Boolean {
        rawTemplates = emptyList()
        try {
            val o = JSONObject(json)
            val v = o.optInt("v", 1)
            if (v < FORMAT_VERSION) return false
            canonicalBands = o.optInt("bands", 16)
            thresholdFactor = o.optDouble("threshold", 1.0)
            sigThreshold = o.optDouble("sigThreshold", 0.60).toFloat()
            val sig = o.optJSONArray("signature")
            enrolledSig = if (sig != null && sig.length() > 0) {
                FloatArray(sig.length()) { sig.optDouble(it, 0.0).toFloat() }
            } else null

            val arr = o.getJSONArray("templates")
            val list = ArrayList<List<FloatArray>>(arr.length())
            for (i in 0 until arr.length()) {
                val framesArr = arr.getJSONArray(i)
                val frames = ArrayList<FloatArray>(framesArr.length())
                for (j in 0 until framesArr.length()) {
                    val f = framesArr.getJSONArray(j)
                    val vec = FloatArray(f.length())
                    for (k in vec.indices) vec[k] = f.optDouble(k, 0.0).toFloat()
                    frames.add(vec)
                }
                if (frames.isNotEmpty()) list.add(frames)
            }
            rawTemplates = list
            return list.isNotEmpty()
        } catch (_: Throwable) {
            return false
        }
    }

    fun toJson(templates: List<List<FloatArray>>, sig: FloatArray?, sigThr: Float): String {
        val o = JSONObject()
        o.put("v", FORMAT_VERSION)
        o.put("bands", canonicalBands)
        o.put("hopMs", 10)
        o.put("threshold", thresholdFactor)
        o.put("sigThreshold", sigThr.toDouble())
        if (sig != null) {
            val s = JSONArray()
            sig.forEach { s.put(it.toDouble()) }
            o.put("signature", s)
        }
        val arr = JSONArray()
        for (t in templates) {
            val framesArr = JSONArray()
            for (f in t) {
                val fArr = JSONArray()
                f.forEach { fArr.put(it.toDouble()) }
                framesArr.put(fArr)
            }
            arr.put(framesArr)
        }
        o.put("templates", arr)
        return o.toString()
    }

    // ── Widoki i progi ────────────────────────────────────────────────────

    /**
     * Przygotowuje widoki dla aktywnego trybu i liczy progi.
     *
     * [channels] = liczba kanałów klatek z mikrofonu (zawsze 16 — patrz komentarz
     * w [WakeEngine.bands]; redukcja do 8 pasm dzieje się w pierwszym stopniu);
     * [timeResample] = 1 dla hop 10 ms, 2 dla hop 20 ms — szablony zapisujemy
     * zawsze w rozdzielczości 10 ms/16 kanałów, więc w trybie ECO trzeba je
     * najpierw „zwolnić" do 20 ms, inaczej mowa brzmiałaby dla DTW dwa razy
     * szybciej i nic by się nie zgadzało;
     * [stride] = co ile klatek pierwszego stopnia liczymy dopasowanie.
     */
    fun derive(channels: Int, stride: Int, timeResample: Int) {
        // ── Stage 2: kanały i tempo trybu + CMVN ──
        stage2Templates = rawTemplates.map { t -> Features.cmvn(toMode(t, channels, timeResample)) }
        spreadStage2 = pairwiseSpread(stage2Templates, MIN_SPREAD_S2)
            .coerceIn(MIN_SPREAD_S2, MAX_SPREAD_S2)

        // ── Stage 1: 8 kanałów, bez średniej ramki, podpróbkowane ──
        // Transformacja jest DOKŁADNIE taka sama jak w silniku dla klatek
        // przychodzących z mikrofonu — inaczej porównywalibyśmy dwie różne rzeczy.
        val ref = rawTemplates.getOrNull(rawTemplates.size / 2) ?: emptyList()
        stage1Template = Features.decimate(toStage1(ref, channels, timeResample), stride)
        val s1All = rawTemplates.map { t -> Features.decimate(toStage1(t, channels, timeResample), stride) }
        spreadStage1 = pairwiseSpread(s1All, MIN_SPREAD_S1)
            .coerceIn(MIN_SPREAD_S1, MAX_SPREAD_S1)
    }

    /** Szablon w rozdzielczości aktywnego trybu (kanały + tempo). */
    private fun toMode(t: List<FloatArray>, channels: Int, timeResample: Int): List<FloatArray> {
        val ch = if (channels < canonicalBands) t.map { Features.halve(it) } else t
        return Features.decimate(ch, timeResample)
    }

    /** Klatki dla pierwszego stopnia: 8 kanałów po odjęciu średniej. */
    private fun toStage1(t: List<FloatArray>, channels: Int, timeResample: Int): List<FloatArray> =
        toMode(t, channels, timeResample).map { meanRemoved(if (it.size > 8) Features.halve(it) else it) }

    private fun meanRemoved(f: FloatArray): FloatArray {
        var s = 0f
        for (v in f) s += v
        val m = s / f.size
        return FloatArray(f.size) { f[it] - m }
    }

    /**
     * Średnia odległość między próbami użytkownika („jak bardzo różnią się
     * moje własne nagrania"). To jest nasza skala: nowa wypowiedź musi być
     * nie dalej niż [thresholdFactor] × ten rozrzut.
     */
    private fun pairwiseSpread(sets: List<List<FloatArray>>, floor: Double): Double {
        if (sets.size < 2) return floor
        var sum = 0.0
        var n = 0
        for (i in sets.indices) {
            for (j in i + 1 until sets.size) {
                sum += Dtw.distance(sets[i], sets[j])
                n++
            }
        }
        if (n == 0) return floor
        val mean = sum / n
        return if (mean < floor) floor else mean
    }

    /** Progi runtime (zależne od czułości ustawionej przez użytkownika). */
    fun stage1Threshold(sensitivity: Float): Double =
        spreadStage1 * S1_FACTOR * (0.9f + sensitivity * 0.4f)

    fun stage2Threshold(sensitivity: Float): Double =
        spreadStage2 * S2_FACTOR * (0.85f + sensitivity * 0.5f) * thresholdFactor

    // ── Negatywy (uczenie się na fałszywych trafieniach) ──────────────────

    fun addNegative(sig: FloatArray) {
        if (sig.isEmpty()) return
        for (n in negatives) {
            if (Features.cosine(n, sig) > 0.98f) return
        }
        negatives.addFirst(sig)
        while (negatives.size > MAX_NEGATIVES) negatives.removeLast()
    }

    /**
     * True, gdy podpis wygląda bardziej jak znane fałszywe trafienie niż jak
     * zarejestrowana fraza — czyli kandydat do odrzucenia.
     */
    fun looksLikeNegative(sig: FloatArray): Boolean {
        val enrolled = enrolledSig ?: return false
        val simEnrolled = Features.cosine(sig, enrolled)
        for (n in negatives) {
            if (Features.cosine(n, sig) > simEnrolled + 0.03f) return true
        }
        return false
    }

    /** Podobieństwo do zarejestrowanego podpisu (null = brak podpisu). */
    fun speakerSimilarity(sig: FloatArray): Float? {
        val enrolled = enrolledSig ?: return null
        return Features.cosine(sig, enrolled)
    }

    fun clearNegatives() = negatives.clear()
}
