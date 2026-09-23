package dev.nixi

import dev.nixi.wake.WakeEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WakeEngineTest {

    private fun frames(values: List<FloatArray>) = values

    private fun seq(seed: Float, n: Int, bands: Int = 16) =
        (0 until n).map { i ->
            FloatArray(bands) { b -> seed + (i + b) % 5 * 0.1f }
        }

    @Test
    fun `identyczne sekwencje maja zerowa odleglosc`() {
        val engine = WakeEngine()
        val a = seq(1f, 30)
        val d = engine.dtw(frames(a), frames(a))
        assertTrue("odległość=$d", abs(d) < 0.0001)
    }

    @Test
    fun `podobne sekwencje sa blizej niz rozne`() {
        val engine = WakeEngine()
        val base = seq(1f, 30)
        val similar = base.map { it.copyOf().also { f -> f[0] = f[0] + 0.02f } }
        val different = seq(4f, 30)
        val dSimilar = engine.dtw(base, similar)
        val dDifferent = engine.dtw(base, different)
        assertTrue("podobne=$dSimilar różne=$dDifferent", dSimilar < dDifferent)
    }

    @Test
    fun `pusta sekwencja nie wywala wyjatku`() {
        val engine = WakeEngine()
        val d = engine.dtw(emptyList(), seq(1f, 10))
        assertTrue(d > 0)
    }

    @Test
    fun `detekcja na cichym sygnale nie daje falszywego trafienia`() {
        val engine = WakeEngine()
        engine.configure(WakeEngine.Mode.STANDARD, 0.5f)
        val silence = ShortArray(16_000) // 1 s ciszy
        var hits = 0
        repeat(10) { if (engine.onPcm(silence, silence.size)) hits++ }
        assertTrue("fałszywe trafienia: $hits", hits == 0)
    }
}
