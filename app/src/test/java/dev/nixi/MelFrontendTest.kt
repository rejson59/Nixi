package dev.nixi

import dev.nixi.wake.Features
import dev.nixi.wake.MelFrontend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Frontend log-mel — ta sama rodzina cech, którą opisuje praca Google o
 * hotwordzie („log of the triangular mel filters applied to the power spectra",
 * okno 25 ms, 16 kHz). Testujemy rzeczy, które realnie psują detekcję:
 * czy FFT trafia w częstotliwość, czy filtry mel mają sens i czy normalizacja
 * nie gubi informacji.
 */
class MelFrontendTest {

    private fun tone(freq: Double, ms: Int, amp: Double = 6000.0, sr: Int = 16_000): ShortArray {
        val n = sr * ms / 1000
        return ShortArray(n) { i ->
            (amp * sin(2.0 * PI * freq * i / sr)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun `ton 1 kHz trafia w odpowiednie pasmo mel`() {
        val fe = MelFrontend(16_000, 16)
        val pcm = tone(1_000.0, 400)
        val f = fe.computeFrame(pcm, 200)

        var loudest = 0
        for (i in f.indices) if (f[i] > f[loudest]) loudest = i
        // 1 kHz w skali mel (16 pasm do 7,6 kHz) leży w okolicy 1/4 pasma
        assertTrue("najgłośniejsze pasmo=$loudest", loudest in 2..7)

        // sąsiednie pasma wokół tonu też są wyraźnie głośniejsze niż skraje
        assertTrue("skraj pasma powinien być cichy", f[0] < f[loudest] - 3f)
    }

    @Test
    fun `cichszy sygnal daje mniejsza log-energie`() {
        val fe = MelFrontend(16_000, 16)
        val loud = fe.computeFrame(tone(800.0, 400, amp = 8000.0), 200)
        val quiet = fe.computeFrame(tone(800.0, 400, amp = 800.0), 200)
        val dl = loud.average()
        val dq = quiet.average()
        assertTrue("głośny=$dl cichy=$dq", dl > dq + 1.5)
    }

    @Test
    fun `cisza nie daje energii`() {
        val fe = MelFrontend(16_000, 16)
        val f = fe.computeFrame(ShortArray(1200), 200)
        for (v in f) assertTrue("wartość=$v", v < -10f)
    }

    @Test
    fun `redukcja kanalow zachowuje ksztalt widma`() {
        val fe = MelFrontend(16_000, 16)
        val f = fe.computeFrame(tone(1_500.0, 400), 200)
        val half = Features.halve(f)
        assertEquals(8, half.size)
        // maksimum w zmniejszonej reprezentacji jest blisko maksimum oryginału
        val maxFull = f.indices.maxByOrNull { f[it] } ?: 0
        val maxHalf = half.indices.maxByOrNull { half[it] } ?: 0
        assertTrue(abs(maxFull / 2 - maxHalf) <= 1)
    }

    @Test
    fun `podprobkowanie w czasie dzieli liczbe klatek`() {
        val frames = (0 until 12).map { FloatArray(4) { b -> (it * 4 + b).toFloat() } }
        val dec = Features.decimate(frames, 3)
        assertEquals(4, dec.size)
        // klatka 0 = średnia z klatek 0,1,2 (kanał 0: (0+4+8)/3 = 4)
        assertEquals(4f, dec[0][0], 0.001f)
        // klatka 1 = średnia z klatek 3,4,5 (kanał 0: (12+16+20)/3 = 16)
        assertEquals(16f, dec[1][0], 0.001f)
    }

    @Test
    fun `cmvn normalizuje srednia i skale`() {
        val frames = (0 until 30).map { i -> FloatArray(4) { b -> 5f + i * 0.1f + b } }
        val n = Features.cmvn(frames)
        // średnia po czasie dla każdego kanału ~ 0
        for (b in 0 until 4) {
            var s = 0f
            for (f in n) s += f[b]
            assertTrue("kanał $b średnia=${s / n.size}", abs(s / n.size) < 1e-3f)
        }
        // skala ~ 1
        val sq = n.sumOf { (it[0] * it[0]).toDouble() } / n.size
        assertTrue("wariancja=$sq", abs(sq - 1.0) < 0.05)
    }

    @Test
    fun `podpis jest podobny dla tych samych cech a rozny dla innych`() {
        val a = (0 until 40).map { i -> FloatArray(8) { b -> 1f + 0.1f * b + i * 0.01f } }
        val b = a.map { it.copyOf().also { f -> f[0] += 0.01f } }
        val c = (0 until 40).map { i -> FloatArray(8) { b -> 4f - 0.2f * b + i * 0.05f } }
        val sa = Features.signature(a)
        val sb = Features.signature(b)
        val sc = Features.signature(c)
        assertTrue("te same: ${Features.cosine(sa, sb)}", Features.cosine(sa, sb) > 0.99f)
        assertTrue("inne: ${Features.cosine(sa, sc)}", Features.cosine(sa, sc) < 0.95f)
    }
}
