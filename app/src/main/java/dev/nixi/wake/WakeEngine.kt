package dev.nixi.wake

import dev.nixi.util.LogBus

/**
 * WYKRYWANIE FRAZY KLUCZOWEJ — architektura kaskadowa wzorowana na hotwordzie
 * Google („A Cascade Architecture for Keyword Spotting on Mobile Devices",
 * Gruenstein i in., NIPS 2017), przełożona na to, co da się zrobić w aplikacji
 * na telefonie, bez wytrenowanej sieci i bez DSP.
 *
 * Jak to działa u Google (i jak to mapujemy):
 *
 *  1. Zawsze włączony pierwszy stopień słucha 16 kHz mono i „buffering enough
 *     audio to safely fit the keyword (typically 2 seconds)" — u nas rolę
 *     bufora DSP pełni [PcmRing] (2 s = 64 kB, dokładnie ta liczba z pracy),
 *     a w tle płacimy tylko za tani zapis i energię ramki.
 *  2. Frontend liczy log-mel („the log of the triangular mel filters applied to
 *     the power spectra", 32–40 kanałów, okno 25 ms) — [MelFrontend].
 *     My liczymy go tylko dla ramek z mową (bramka [VadGate]) i nadrabiamy
 *     wstecz z bufora, żeby początek frazy nie zniknął.
 *  3. Pierwszy stopień jest mały i ma wysoką czułość (u Google: maleńki model
 *     na DSP, tunowany tak, by budził procesor „a handful of times per hour").
 *     U nas: dopasowanie DTW na zredukowanych cechach (8 kanałów, co ~30 ms)
 *     do jednego szablonu referencyjnego, z luźnym progiem.
 *  4. Drugi stopień uruchamia się dopiero po kandydacie i decyduje ostatecznie
 *     większym modelem na pełnych cechach — u nas: DTW na 16 kanałach,
 *     na wszystkich 3 szablonach, z automatycznym doborem długości okna.
 *  5. Trzeci filtr (u Google weryfikacja mówcy, „reduces FAR by a factor of
 *     5 to 10") — u nas porównanie podpisu widmowego z zarejestrowanym
 *     podpisem użytkownika oraz lista negatywów z odrzuconych kandydatów.
 *  6. Dekoder u Google wygładza posteriory po oknie i wymaga, by jednostki
 *     „zapaliły się" w kolejności — u nas odpowiednikiem jest potwierdzenie:
 *     dwa trafienia drugiego stopnia w odstępie ≥100 ms, z histerezą.
 *
 * Efekt: w ciszy koszt to energia ramki (kilkaset mnożeń), pełny frontend mel
 * startuje tylko na mowę, a fałszywe trafienia odsiewa drugi i trzeci filtr.
 */
class WakeEngine {

    enum class Mode { STANDARD, ECO }

    /**
     * Statystyki pracy detektora — do logów i do dostrajania na telefonie.
     * Dzięki nim widać np. „kandydatów: 12, odrzucone po podpisie: 9".
     */
    data class Stats(
        var frames: Long = 0,
        var silentFrames: Long = 0,
        var speechFrames: Long = 0,
        var stage1Candidates: Long = 0,
        var stage2Runs: Long = 0,
        var stage2Passed: Long = 0,
        var rejectsSpeaker: Long = 0,
        var rejectsNegative: Long = 0,
        var rejectsNoConfirm: Long = 0,
        var hits: Long = 0,
        var lastStage2Score: Double = 0.0,
        var lastSpread: Double = 0.0,
    ) {
        fun summary(): String =
            "ramki=$frames (cisza=$silentFrames, mowa=$speechFrames) " +
                "kandydaci=$stage1Candidates drugi_stopien=$stage2Runs " +
                "przeszlo=$stage2Passed odrzucone[glos=$rejectsSpeaker " +
                "negatywy=$rejectsNegative brak_potwierdzenia=$rejectsNoConfirm] " +
                "trafienia=$hits ostatni_wynik=%.2f prog=%.2f".format(lastStage2Score, lastSpread)
    }

    var mode = Mode.STANDARD
        private set
    var sensitivity = 0.5f
        private set

    /**
     * Kanały frontendu mel — zawsze 16, także w trybie ECO.
     *
     * Uwaga na przyszłość: NIE wolno zmieniać tej liczby w trybie ECO.
     * Szablony zapisujemy raz, w kanonicznej postaci 16 kanałów, a „oszczędność
     * kanałów" robimy w warstwie klasyfikatora (pierwszy stopień widzi 8 pasm
     * po złożeniu parami). Gdyby ECO liczyło 8 filtrów mel wprost z widma,
     * porównywalibyśmy klatki z dwóch różnych definicji pasm i detektor
     * przestałby działać — dokładnie to pokazała symulacja kaskady.
     * Energię oszczędzamy więc tam, gdzie jest jej najwięcej: hop 20 ms
     * zamiast 10 ms to o połowę mniej FFT, ocen drugiego stopnia i wybudzeń.
     */
    val bands: Int = 16

    /** Odstęp klatek frontendu (STANDARD 10 ms, ECO 20 ms). */
    val hopMs: Int get() = if (mode == Mode.STANDARD) 10 else 20

    val stats = Stats()

    private val model = WakeModel()

    private var frontend = MelFrontend(16_000, bands)
    private var hopSamples = hopMs * 16
    private var stride = if (mode == Mode.STANDARD) 3 else 2
    private var timeResample = 1

    private var ring = PcmRing(16 * PREROLL_MS)
    private val vad = VadGate(hopMs = hopMs)
    private var floor = SpectralFloor(bands)
    private val scratch = ShortArray(MelFrontend.WINDOW_SAMPLES + 2)

    /** Historia cech pełnej rozdzielczości (drugi stopień). */
    private val frames = ArrayDeque<FloatArray>()
    private val s1frames = ArrayDeque<FloatArray>()

    private var nextPos = 0L
    private var tailFrames = 0
    private var silenceTick = 0

    private var lastHitAt = 0L
    private var lastStage2At = 0L
    private var votes = 0
    private var lastVoteAt = 0L
    private var pendingSig: FloatArray? = null
    private var pendingSince = 0L

    @Volatile private var needsReenroll = false

    /** True, gdy wykryto stary format szablonu — trzeba zarejestrować frazę od nowa. */
    val requiresReenroll: Boolean get() = needsReenroll

    companion object {
        /** Bufor audio „na frazę" — u Google 2 s, u nas tyle samo. */
        const val PREROLL_MS = 2000

        /** Ile jeszcze liczymy klatki po zamknięciu bramki (ogon frazy). */
        private const val TAIL_MS = 400

        /** Odstęp między ocenami drugiego stopnia (oszczędza CPU). */
        private const val STAGE2_MIN_GAP_MS = 30L

        /** Potwierdzenie: dwa trafienia ≥100 ms od siebie, w oknie 1,5 s. */
        private const val VOTE_GAP_MS = 100L
        private const val VOTE_WINDOW_MS = 1500L

        /** Po trafieniu przez chwilę nie reagujemy. */
        private const val COOLDOWN_MS = 12_000L

        /** Historia cech: 2,5 s przy hop 10 ms. */
        private const val MAX_FRAMES = 250

        /** Co ile ramek ciszy liczymy mel wyłącznie po to, by uczyć tło. */
        private const val SILENCE_MEL_EVERY = 5

        /** Długości okna próbowane w drugim stopniu (tempo mowy bywa różne). */
        private val LENGTH_FACTORS = doubleArrayOf(0.8, 1.0, 1.2)
    }

    // ── Konfiguracja ──────────────────────────────────────────────────────

    @Synchronized
    fun configure(mode: Mode, sensitivity: Float) {
        this.mode = mode
        this.sensitivity = sensitivity
        rebuild()
    }

    private fun rebuild() {
        frontend = MelFrontend(16_000, bands)
        hopSamples = hopMs * 16
        stride = if (mode == Mode.STANDARD) 3 else 2
        // szablony zapisujemy w jednostkach 10 ms, więc tryb ECO o hopie 20 ms
        // musi je najpierw „zwolnić" — inaczej DTW porównywałby dwa różne tempa
        timeResample = if (mode == Mode.STANDARD) 1 else 2
        ring = PcmRing(16 * PREROLL_MS)
        floor = SpectralFloor(bands)
        frames.clear()
        s1frames.clear()
        nextPos = 0
        tailFrames = 0
        silenceTick = 0
        votes = 0
        pendingSig = null
        vad.reset()
        model.derive(bands, stride, timeResample)
    }

    @Synchronized
    fun loadFromJson(json: String) {
        val ok = model.loadFromJson(json)
        if (ok) {
            needsReenroll = false
            model.derive(bands, stride, timeResample)
            LogBus.log(
                "wake.templates",
                "wczytano ${model.rawTemplates.size} szablonów (rozrzut s2=" +
                    "%.3f, podpis=${if (model.enrolledSig != null) "jest" else "brak"})".format(model.spreadStage2)
            )
        } else if (json.isNotBlank()) {
            // Stary detektor używał cech Goertzla — nowy frontend mel jest
            // niekompatybilny, więc zamiast cicho nie działać prosimy o nagranie.
            needsReenroll = true
            LogBus.log(
                "wake.templates",
                "stary format szablonu — zarejestruj „Hej Nixi” ponownie (nowy detektor)",
                "warn"
            )
        }
    }

    @Synchronized
    fun hasTemplates(): Boolean = model.hasTemplates()

    /**
     * Wczytuje szablony wprost z cech (po rejestracji lub z testów).
     * [sigThreshold] to próg trzeciego filtru (podpis), [signature] — podpis
     * mówcy uśredniony z prób rejestracji.
     */
    @Synchronized
    fun loadTemplates(
        templates: List<List<FloatArray>>,
        signature: FloatArray?,
        sigThreshold: Float,
    ) {
        model.setTemplates(templates, signature, sigThreshold)
        needsReenroll = false
        model.derive(bands, stride, timeResample)
        LogBus.log(
            "wake.templates",
            "wczytano ${model.rawTemplates.size} szablonów (rozrzut s2=%.3f)".format(model.spreadStage2)
        )
    }

    /** Zapis szablonów (jedyny format, jaki czyta [loadFromJson]). */
    fun toJson(
        templates: List<List<FloatArray>>,
        sigThreshold: Float,
        signature: FloatArray?,
    ): String = model.toJson(templates, signature, sigThreshold)

    fun statsSummary(): String = stats.summary()

    // ── Wejście PCM ───────────────────────────────────────────────────────

    /**
     * Karmi detektor kolejną porcją PCM 16 kHz mono.
     * Zwraca true, gdy fraza kluczowa została potwierdzona.
     */
    @Synchronized
    fun onPcm(pcm: ShortArray, len: Int): Boolean {
        if (len <= 0) return false
        ring.write(pcm, len)

        val win = MelFrontend.WINDOW_SAMPLES
        if (nextPos == 0L) {
            nextPos = maxOf(ring.oldest(), ring.total - win)
        }
        if (nextPos < ring.oldest()) nextPos = ring.oldest()

        var hit = false
        while (ring.has(nextPos, win)) {
            if (processAt(nextPos)) hit = true
            nextPos += hopSamples
        }
        return hit
    }

    private fun processAt(pos: Long): Boolean {
        stats.frames++
        // energia ramki liczymy z tych samych próbek, których użyje frontend
        val from = if (pos > 0) pos - 1 else pos
        val need = if (pos > 0) MelFrontend.WINDOW_SAMPLES + 1 else MelFrontend.WINDOW_SAMPLES
        if (!ring.read(from, scratch, need)) return false
        val frameOffset = if (pos > 0) 1 else 0

        val db = vad.levelDb(scratch, frameOffset, MelFrontend.WINDOW_SAMPLES)
        val wasOpen = vad.open
        val open = vad.update(db)

        if (vad.justOpened) {
            stats.speechFrames++
            // Nadrób klatki sprzed otwarcia bramki — dokładnie po to jest bufor
            // 2 s: pierwsza sylaba frazy nie może wypaść.
            backfill(pos)
            tailFrames = TAIL_MS / hopMs
            return appendAndEvaluate(frontend.computeFrame(scratch, frameOffset), pos)
        }

        if (open) {
            stats.speechFrames++
            tailFrames = TAIL_MS / hopMs
            val feat = frontend.computeFrame(scratch, frameOffset)
            return appendAndEvaluate(feat, pos)
        }

        if (!wasOpen) stats.silentFrames++
        if (tailFrames > 0) {
            // ogon frazy: dokończ dopasowanie, żeby złapać koniec słowa
            tailFrames--
            val feat = frontend.computeFrame(scratch, frameOffset)
            return appendAndEvaluate(feat, pos)
        }

        // cisza: raz na kilka ramek uczymy tło widmowe (koszt ~1 FFT/50–100 ms)
        silenceTick++
        if (silenceTick % SILENCE_MEL_EVERY == 0) {
            floor.observe(frontend.computeFrame(scratch, frameOffset))
        }
        return false
    }

    /** Klatki z bufora sprzed otwarcia bramki (pre-roll). */
    private fun backfill(openPos: Long) {
        val count = PREROLL_MS / hopMs
        var p = maxOf(ring.oldest(), openPos - count.toLong() * hopSamples)
        while (p < openPos) {
            val from = if (p > 0) p - 1 else p
            val need = if (p > 0) MelFrontend.WINDOW_SAMPLES + 1 else MelFrontend.WINDOW_SAMPLES
            if (ring.read(from, scratch, need)) {
                val feat = frontend.computeFrame(scratch, if (p > 0) 1 else 0)
                pushFrames(feat)
            }
            p += hopSamples
        }
        LogBus.log("wake.gate", "mowa — nadrobiono ${frames.size} klatek z bufora", "ok")
    }

    private fun appendAndEvaluate(feat: FloatArray, pos: Long): Boolean {
        val f = floor.subtract(feat)
        pushFrames(f)
        return evaluate(pos)
    }

    private fun pushFrames(f: FloatArray) {
        frames.addLast(f)
        while (frames.size > MAX_FRAMES) frames.removeFirst()
        // Klatki pierwszego stopnia trzymamy w pełnym tempie, a decymację
        // robimy dopiero przy budowie okna — musi być identyczna z tą, którą
        // przeszły szablony w [WakeModel.derive] (średnia z grup klatek).
        val pooled = if (f.size > 8) Features.halve(f) else f
        var sum = 0f
        for (v in pooled) sum += v
        val m = sum / pooled.size
        s1frames.addLast(FloatArray(pooled.size) { pooled[it] - m })
        while (s1frames.size > MAX_FRAMES) s1frames.removeFirst()
    }

    // ── Kaskada ───────────────────────────────────────────────────────────

    private fun evaluate(pos: Long): Boolean {
        if (!model.hasTemplates()) return false
        val now = System.currentTimeMillis()
        if (now - lastHitAt < COOLDOWN_MS) return false

        // STAGE 1 — tani, ciągły, wysoka czułość
        val d1 = stage1Distance() ?: return false
        if (d1 > model.stage1Threshold(sensitivity)) {
            expirePending(now)
            return false
        }
        stats.stage1Candidates++

        // STAGE 2 — dokładny, tylko po kandydacie
        if (now - lastStage2At < STAGE2_MIN_GAP_MS) return false
        lastStage2At = now
        val nowMs = (pos / 16L)
        val check = stage2Verify() ?: return false
        stats.stage2Runs++
        stats.lastStage2Score = check.score
        stats.lastSpread = model.spreadStage2
        val thr2 = model.stage2Threshold(sensitivity)
        if (check.score > thr2) {
            // Histereza: wyraźnie zły kandydat kasuje głosy potwierdzenia,
            // czyli „wycofujemy" nadzieję, że to ta fraza
            if (check.score > thr2 * 1.3) {
                votes = 0
                pendingSig = null
            }
            expirePending(now)
            return false
        }
        stats.stage2Passed++

        // TRZECI FILTR — podpis mówcy i nauczone negatywy
        val sig = check.signature
        model.speakerSimilarity(sig)?.let { sim ->
            if (sim < model.sigThreshold) {
                stats.rejectsSpeaker++
                LogBus.log(
                    "wake.reject",
                    "podpis %.2f < %.2f — to nie Twoje „Hej Nixi”".format(sim, model.sigThreshold),
                    "ok"
                )
                expirePending(now)
                return false
            }
        }
        if (model.looksLikeNegative(sig)) {
            stats.rejectsNegative++
            LogBus.log("wake.reject", "wygląda jak znane fałszywe trafienie", "ok")
            expirePending(now)
            return false
        }

        // POTWIERDZENIE — odpowiednik wygładzania posteriorów w dekoderze
        if (nowMs - lastVoteAt >= VOTE_GAP_MS) {
            votes = if (nowMs - lastVoteAt <= VOTE_WINDOW_MS) votes + 1 else 1
            lastVoteAt = nowMs
            pendingSig = sig
            pendingSince = now
        }
        if (votes >= 2) {
            votes = 0
            pendingSig = null
            stats.hits++
            lastHitAt = now
            frames.clear()
            s1frames.clear()
            LogBus.log(
                "wake.hit",
                "potwierdzone: wynik=%.2f próg=%.2f rozrzut=%.3f".format(
                    check.score, thr2, model.spreadStage2
                )
            )
            return true
        }
        return false
    }

    /**
     * Kandydat, który nie doczekał się potwierdzenia, jest dla nas cenną
     * informacją: to niemal na pewno fałszywe trafienie. Zapisujemy jego podpis
     * jako negatyw, żeby drugi raz nie dał się nabrać (uczenie na urządzeniu).
     */
    private fun expirePending(now: Long) {
        val sig = pendingSig ?: return
        if (now - pendingSince < VOTE_WINDOW_MS) return
        model.addNegative(sig)
        stats.rejectsNoConfirm++
        pendingSig = null
        votes = 0
        LogBus.log("wake.negative", "nauczone nowe fałszywe trafienie (razem ${model.negativeCount()})")
    }

    /**
     * Pierwszy stopień: DTW na 8 kanałach z decymacją czasu (u Google mały
     * model na DSP ma „zlapać" kandydata tanim kosztem, a decyduje drugi).
     */
    private fun stage1Distance(): Double? {
        val t = model.stage1Template
        if (t.isEmpty()) return null
        val need = t.size * stride
        if (s1frames.size < need) return null
        val start = s1frames.size - need
        val bands1 = t[0].size
        val window = ArrayList<FloatArray>(t.size)
        for (i in 0 until t.size) {
            val acc = FloatArray(bands1)
            for (j in 0 until stride) {
                val f = s1frames.elementAt(start + i * stride + j)
                for (b in 0 until bands1) acc[b] += if (b < f.size) f[b] else 0f
            }
            val inv = 1f / stride
            for (b in 0 until bands1) acc[b] *= inv
            window.add(acc)
        }
        return Dtw.distance(window, t)
    }

    private data class Stage2Result(val score: Double, val signature: FloatArray)

    /**
     * Drugi stopień: pełne cechy (16 kanałów), wszystkie szablony, automatyczny
     * dobór długości okna (mówimy w różnym tempie) i CMVN jak w frontendzie Google.
     */
    private fun stage2Verify(): Stage2Result? {
        if (model.stage2Templates.isEmpty()) return null
        var best = Double.MAX_VALUE
        var bestFrames: List<FloatArray>? = null
        for (t in model.stage2Templates) {
            val lt = t.size
            if (lt < 10) continue
            for (factor in LENGTH_FACTORS) {
                val len = (lt * factor).toInt()
                if (len < 10 || len > frames.size) continue
                val window = ArrayList<FloatArray>(len)
                var i = frames.size - len
                while (i < frames.size) {
                    window.add(frames.elementAt(i))
                    i++
                }
                val norm = Features.cmvn(window)
                val d = Dtw.distance(norm, t)
                if (d < best) {
                    best = d
                    bestFrames = window
                }
            }
        }
        if (best == Double.MAX_VALUE || bestFrames == null) return null
        // podpis liczymy na cechach surowych (bez CMVN) — niesie informację
        // o barwie głosu i mikrofonie, czego normalizacja by się pozbyła
        return Stage2Result(best, Features.signature(bestFrames))
    }
}

/**
 * Rejestrowanie wzorca frazy (3 próby) — wspólne dla onboardingu i ustawień.
 * Cechy liczy ten sam frontend mel, którego używa detektor, więc nie ma
 * „przesunięcia" między rejestracją a działaniem.
 */
object WakeEnroll {

    data class AttemptResult(val frames: List<FloatArray>, val ok: Boolean, val note: String)

    /** Kanały zapisywane w szablonie (kanoniczne). */
    const val CANONICAL_BANDS = 16

    /**
     * Przetwarza nagraną próbkę (1,5–2,5 s PCM 16 kHz) i zwraca obcięte cechy
     * log-mel, z odjętym tłem (odpowiednik „spectral subtraction" z pracy).
     */
    fun processAttempt(pcm: ShortArray, pcmLen: Int, bands: Int = CANONICAL_BANDS): AttemptResult {
        val frontend = MelFrontend(16_000, bands)
        val hop = 160
        val win = MelFrontend.WINDOW_SAMPLES
        if (pcmLen < win + hop * 20) {
            return AttemptResult(emptyList(), false, "zbyt krótkie nagranie — powiedz „Hej Nixi”")
        }

        val floor = SpectralFloor(bands)
        val gate = VadGate(hopMs = 10)
        val feats = ArrayList<FloatArray?>(pcmLen / hop)
        var loud = 0
        var pos = 1
        while (pos + win < pcmLen) {
            val db = gate.levelDb(pcm, pos, win)
            val open = gate.update(db)
            val raw = frontend.computeFrame(pcm, pos)
            if (!open) {
                // tło uczymy się tylko z ciszy (tak samo jak w detektorze)
                floor.observe(raw)
                feats.add(null)
                pos += hop
                continue
            }
            loud++
            feats.add(floor.subtract(raw).copyOf())
            pos += hop
        }
        if (loud < 15) {
            return AttemptResult(emptyList(), false, "nie wykryto mowy — spróbuj głośniej i bliżej mikrofonu")
        }

        // obetnij ciszę na krańcach (z małym marginesem)
        val first = feats.indexOfFirst { it != null }
        val last = feats.indexOfLast { it != null }
        if (first < 0 || last <= first) {
            return AttemptResult(emptyList(), false, "nie wykryto mowy — spróbuj jeszcze raz")
        }
        val from = maxOf(0, first - 5)
        val to = minOf(feats.size - 1, last + 5)
        val kept = ArrayList<FloatArray>(to - from + 1)
        for (i in from..to) feats[i]?.let { kept.add(it) }
        if (kept.size < 20) {
            return AttemptResult(emptyList(), false, "zbyt krótka wypowiedź — powiedz całe „Hej Nixi”")
        }
        // równe tempo: uśrednij skrajne ramki, żeby szablon nie miał „dziur”
        return AttemptResult(kept, true, "ok (${kept.size} klatek)")
    }

    /** Podpis mówcy z szablonu (średnia + odchylenie kanałów). */
    fun signatureOf(frames: List<FloatArray>): FloatArray = Features.signature(frames)

    /** Średni podpis z 3 prób. */
    fun meanSignature(templates: List<List<FloatArray>>): FloatArray? =
        Features.meanSignature(templates.map { Features.signature(it) })

    /**
     * Próg podobieństwa podpisu: bierzemy najgorsze podobieństwo między
     * własnymi próbami użytkownika i cofamy się o margines. Dzięki temu próg
     * nie jest liczbą z sufitu, tylko wynika z tego, jak stabilnie mówisz.
     */
    fun signatureThreshold(templates: List<List<FloatArray>>): Float {
        val sigs = templates.map { Features.signature(it) }.filter { it.isNotEmpty() }
        if (sigs.size < 2) return 0.60f
        var minSim = 1f
        for (i in sigs.indices) {
            for (j in i + 1 until sigs.size) {
                val s = Features.cosine(sigs[i], sigs[j])
                if (s < minSim) minSim = s
            }
        }
        return (minSim - 0.10f).coerceIn(0.55f, 0.90f)
    }

    /**
     * Rozrzut między próbami użytkownika w przestrzeni drugiego stopnia
     * (CMVN). Detektor używa tej liczby jako skali progu, więc im stabilniej
     * mówisz, tym ostrzejszy próg — bez zgadywania wartości z sufitu.
     */
    fun spread(templates: List<List<FloatArray>>): Double {
        if (templates.size < 2) return WakeModel.MIN_SPREAD_S2
        val norm = templates.map { Features.cmvn(it) }
        var sum = 0.0
        var n = 0
        for (i in norm.indices) {
            for (j in i + 1 until norm.size) {
                sum += Dtw.distance(norm[i], norm[j])
                n++
            }
        }
        return if (n > 0) sum / n else 0.0
    }
}
