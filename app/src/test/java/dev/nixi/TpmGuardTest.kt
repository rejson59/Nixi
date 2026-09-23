package dev.nixi

import dev.nixi.live.TpmGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TpmGuardTest {

    @Test
    fun `limit off nie tnie niczego`() {
        val g = TpmGuard { Int.MAX_VALUE }
        assertTrue(g.canSend(1_000_000))
        g.addUsed(1_000_000)
        assertTrue(g.canSend(1_000_000))
    }

    @Test
    fun `budzet w oknie 60 s jest respektowany`() {
        val g = TpmGuard { 1000 }
        assertEquals(1000, g.limitTokens())
        g.addUsed(700)
        assertTrue(g.canSend(300))
        assertFalse(g.canSend(301))
        assertEquals(700, g.snapshot().used)
    }

    @Test
    fun `429 blokuje wysylke i pokazuje backoff`() {
        val g = TpmGuard { 100_000 }
        assertTrue(g.canSend(10))
        g.noteRateLimit(60_000)
        assertFalse(g.canSend(10))
        assertTrue(g.snapshot().backoffSec in 1..60)
    }

    @Test
    fun `szacunki tokenow sa dodatnie i rosna z dlugoscia`() {
        assertTrue(TpmGuard.tokensForAudioSeconds(0.064) >= 1)
        assertTrue(TpmGuard.tokensForAudioSeconds(1.0) > TpmGuard.tokensForAudioSeconds(0.5))
        assertTrue(TpmGuard.tokensForOutputSeconds(1.0) > 0)
        assertTrue(TpmGuard.tokensForText("krótko") >= 1)
        assertTrue(TpmGuard.tokensForText("x".repeat(400)) > TpmGuard.tokensForText("xxxx"))
        assertTrue(TpmGuard.tokensForImage(1152, 2400) >= 50)
    }

    @Test
    fun `procent zuzycia liczy sie poprawnie`() {
        val g = TpmGuard { 1000 }
        g.addUsed(250)
        assertEquals(25, g.snapshot().percent)
    }

    @Test
    fun `reset czysci okno`() {
        val g = TpmGuard { 1000 }
        g.addUsed(900)
        g.noteRateLimit(1000)
        g.reset()
        assertEquals(0, g.snapshot().used)
        assertEquals(0, g.snapshot().backoffSec)
        assertTrue(g.canSend(900))
    }

    @Test
    fun `wielowatkowe doliczanie nie gubi tokenow`() {
        val g = TpmGuard { 100_000 }
        val threads = (1..4).map {
            Thread { repeat(250) { g.addUsed(1) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
        assertEquals(1000, g.snapshot().used)
    }
}
