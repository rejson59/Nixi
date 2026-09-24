package dev.nixi

import dev.nixi.wake.PcmRing
import dev.nixi.wake.VadGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WakeComponentsTest {

    @Test
    fun `bufor kolowy trzyma ostatnie probki takze po zawinieciu`() {
        val ring = PcmRing(10)
        // 24 próbki do bufora o pojemności 10 → w pamięci zostaje 14..23
        val src = ShortArray(24) { it.toShort() }
        ring.write(src, src.size)
        assertEquals(24L, ring.total)
        assertEquals(14L, ring.oldest())
        val out = ShortArray(10)
        assertTrue(ring.read(14, out, 10))
        for (i in 0 until 10) assertEquals((14 + i).toShort(), out[i])
        // zakres sprzed okna już nie istnieje
        assertFalse(ring.read(13, out, 1))
    }

    @Test
    fun `bufor czyta poprawnie przez granice pierscienia`() {
        val ring = PcmRing(8)
        ring.write(ShortArray(6) { (100 + it).toShort() }, 6)
        ring.write(ShortArray(6) { (200 + it).toShort() }, 6)   // zawija
        val out = ShortArray(6)
        assertTrue(ring.read(6, out, 6))
        for (i in 0 until 6) assertEquals((200 + i).toShort(), out[i])
    }

    private fun loud(ms: Int, amp: Double = 9000.0): ShortArray {
        val n = 16 * ms
        return ShortArray(n) { i -> (amp * sin(2.0 * PI * 300.0 * i / 16_000)).toInt().toShort() }
    }

    private fun quiet(ms: Int): ShortArray {
        val n = 16 * ms
        return ShortArray(n) { i -> ((i % 7) - 3).toShort() }   // bardzo cichy szum
    }

    @Test
    fun `bramka nie otwiera sie na szumie`() {
        val gate = VadGate(hopMs = 10)
        val noise = quiet(10)
        var opened = false
        repeat(200) { if (gate.update(gate.levelDb(noise, 0, noise.size))) opened = true }
        assertFalse("bramka otworzyła się na samym szumie", opened)
    }

    @Test
    fun `bramka otwiera sie na mowie i zamyka po ciszy`() {
        val gate = VadGate(hopMs = 10)
        val noise = quiet(10)
        val speech = loud(10)
        // rozgrzewka + tło
        repeat(60) { gate.update(gate.levelDb(noise, 0, noise.size)) }
        assertFalse(gate.open)

        var openedAfter = -1
        for (i in 0 until 10) {
            if (gate.update(gate.levelDb(speech, 0, speech.size)) && openedAfter < 0) openedAfter = i
        }
        assertTrue("bramka nie otworzyła się na mowie (i=$openedAfter)", openedAfter in 0..3)

        // histereza: krótka pauza (100 ms) nie zamyka bramki...
        repeat(10) { gate.update(gate.levelDb(noise, 0, noise.size)) }
        assertTrue("bramka zamknęła się za szybko", gate.open)
        // ...ale 500 ms ciszy już tak
        repeat(50) { gate.update(gate.levelDb(noise, 0, noise.size)) }
        assertFalse("bramka nie zamknęła się po ciszy", gate.open)
    }

    @Test
    fun `rozgrzewka nie pozwala na trafienie na starcie`() {
        val gate = VadGate(hopMs = 10)
        val speech = loud(10)
        // pierwsze ramki są głośne (np. start aplikacji) — i tak nie otwieramy
        assertFalse(gate.update(gate.levelDb(speech, 0, speech.size)))
        assertFalse(gate.update(gate.levelDb(speech, 0, speech.size)))
    }
}
