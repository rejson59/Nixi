package dev.nixi

import dev.nixi.wake.WakeEnroll
import dev.nixi.wake.WakeEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Test kaskady „na sucho", bez telefonu: syntetyczne „słowo" (ciąg sylab
 * o różnych formantach) rejestrujemy 3 razy, a potem sprawdzamy, czy detektor
 * je rozpoznaje, a innego słowa — nie.
 *
 * To nie zastępuje testu z prawdziwym głosem, ale łapie rzeczy, które psują
 * detekcję zawsze: złe progi, zepsute nadrabianie z bufora, złą normalizację.
 */
class WakeCascadeTest {

    /** Sylaba: kilka harmonicznych („formantów") wypełniających pasmo. */
    private fun syllable(start: Int, ms: Int, formants: DoubleArray, sr: Int = 16_000): ShortArray {
        val n = sr * ms / 1000
        return ShortArray(n) { i ->
            var v = 0.0
            for ((k, f) in formants.withIndex()) {
                val amp = 1.0 / (k + 1)
                v += amp * sin(2.0 * PI * f * i / sr)
            }
            // obwiednia z miękkim atakiem/wygaśnięciem — jak sylaba
            val env = sin(PI * i / n).coerceAtLeast(0.0)
            (v / formants.size * 12_000.0 * env).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun noise(ms: Int, rnd: Random, amp: Double = 120.0): ShortArray {
        val n = 16 * ms
        return ShortArray(n) { ((rnd.nextDouble() * 2 - 1) * amp).toInt().toShort() }
    }

    /**
     * Buduje nagranie: 300 ms tła, potem sylaby, na końcu 200 ms tła.
     * [formants] i [gaps] opisują „słowo", więc inne zestawy = inne słowo.
     */
    private fun utterance(
        formants: List<DoubleArray>,
        syllMs: List<Int>,
        rnd: Random,
    ): ShortArray {
        val chunks = ArrayList<ShortArray>()
        chunks.add(noise(300, rnd))
        for (i in formants.indices) {
            chunks.add(syllable(0, syllMs[i], formants[i]))
            if (i < formants.size - 1) chunks.add(noise(minOf(120, syllMs[i] / 2), rnd))
        }
        chunks.add(noise(250, rnd))
        val total = chunks.sumOf { it.size }
        val out = ShortArray(total)
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, pos, c.size)
            pos += c.size
        }
        return out
    }

    /** Nasze „Hej Nixi" w wersji syntetycznej: 4 sylaby, rosnące formanty. */
    private fun keyword(rnd: Random): ShortArray = utterance(
        listOf(
            doubleArrayOf(320.0, 900.0, 2100.0),
            doubleArrayOf(500.0, 1400.0, 2600.0),
            doubleArrayOf(700.0, 1100.0, 1800.0),
            doubleArrayOf(420.0, 1600.0, 2900.0),
        ),
        listOf(180, 160, 150, 220),
        rnd,
    )

    /** Inne słowo o podobnej długości, ale innym kształcie widma. */
    private fun otherWord(rnd: Random): ShortArray = utterance(
        listOf(
            doubleArrayOf(160.0, 700.0, 1500.0),
            doubleArrayOf(240.0, 500.0, 1200.0),
            doubleArrayOf(300.0, 800.0, 1900.0),
            doubleArrayOf(200.0, 600.0, 1300.0),
        ),
        listOf(120, 200, 130, 240),
        rnd,
    )

    /**
     * Rejestruje frazę 3 próbami i ładuje szablon do silnika.
     * Świadomie bez JSON-a: na JVM `org.json` jest zaślepką, więc testujemy
     * logikę detektora, a nie serializację (ta ma własne zabezpieczenie:
     * nieznany format => prośba o ponowną rejestrację).
     */
    private fun enroll(engine: WakeEngine, seedBase: Int) {
        val attempts = (0 until 3).map { i ->
            val pcm = keyword(Random(seedBase + i))
            val res = WakeEnroll.processAttempt(pcm, pcm.size)
            assertTrue("rejestracja próby $i nieudana: ${res.note}", res.ok)
            res.frames
        }
        val sig = WakeEnroll.meanSignature(attempts)
        val sigThr = WakeEnroll.signatureThreshold(attempts)
        engine.loadTemplates(attempts, sig, sigThr)
    }

    @Test
    fun `detektor rozpoznaje zarejestrowana fraze`() {
        val engine = WakeEngine()
        engine.configure(WakeEngine.Mode.STANDARD, 0.5f)
        enroll(engine, seedBase = 100)
        assertTrue("brak szablonów po wczytaniu", engine.hasTemplates())

        val test = keyword(Random(777))
        var hit = false
        var i = 0
        while (i < test.size && !hit) {
            val len = minOf(1024, test.size - i)
            val chunk = ShortArray(len)
            System.arraycopy(test, i, chunk, 0, len)
            if (engine.onPcm(chunk, len)) hit = true
            i += len
        }
        assertTrue("nie rozpoznano frazy (stats: ${engine.statsSummary()})", hit)
    }

    @Test
    fun `inne slowo nie wywoluje detekcji`() {
        val engine = WakeEngine()
        engine.configure(WakeEngine.Mode.STANDARD, 0.5f)
        enroll(engine, seedBase = 200)

        var hits = 0
        for (seed in listOf(11, 22, 33, 44)) {
            val other = otherWord(Random(seed))
            var i = 0
            while (i < other.size) {
                val len = minOf(1024, other.size - i)
                val chunk = ShortArray(len)
                System.arraycopy(other, i, chunk, 0, len)
                if (engine.onPcm(chunk, len)) hits++
                i += len
            }
        }
        assertFalse("fałszywe trafienia: $hits (stats: ${engine.statsSummary()})", hits > 0)
    }

    @Test
    fun `cisza i szum nie wywoluja detekcji`() {
        val engine = WakeEngine()
        engine.configure(WakeEngine.Mode.STANDARD, 0.5f)
        enroll(engine, seedBase = 300)

        var hits = 0
        val rnd = Random(5)
        repeat(30) {
            val n = noise(500, rnd, amp = 400.0)
            if (engine.onPcm(n, n.size)) hits++
        }
        assertFalse("fałszywe trafienia na szumie: $hits", hits > 0)
    }

    @Test
    fun `stary format szablonu jest odrzucany i widac flage rejestracji`() {
        val engine = WakeEngine()
        engine.configure(WakeEngine.Mode.STANDARD, 0.5f)
        // format v1 = stary detektor Goertzel (inne cechy, niekompatybilne)
        val legacy = """{"bands":16,"hopMs":10,"threshold":0.45,"templates":[[[0.1,0.2]]]}"""
        engine.loadFromJson(legacy)
        assertFalse(engine.hasTemplates())
        assertTrue(engine.requiresReenroll)
    }
}
