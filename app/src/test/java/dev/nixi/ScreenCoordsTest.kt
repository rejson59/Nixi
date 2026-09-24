package dev.nixi

import dev.nixi.util.ScreenCoords
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Współrzędne z trybu ręcznego: model podaje raz piksele, raz procenty.
 * Ten kod już raz wysyłał klik w lewy górny róg (brak współrzędnych = 0,0),
 * dlatego jest teraz czystą funkcją z testami.
 */
class ScreenCoordsTest {

    // Xiaomi Redmi Note 14 Pro 5G (1080x2400 = 1220x2712 px? — tu realistyczne wartości)
    private val w = 1220
    private val h = 2712

    @Test
    fun `male liczby w obu osiach to procenty ekranu`() {
        val (x, y) = ScreenCoords.pair(50, 50, w, h)
        assertEquals(610, x)
        assertEquals(1356, y)
    }

    @Test
    fun `duze liczby to piksele i nie sa skalowane`() {
        val (x, y) = ScreenCoords.pair(900, 1800, w, h)
        assertEquals(900, x)
        assertEquals(1800, y)
    }

    @Test
    fun `jedna wspolrzedna mala a druga duza - brak skalowania`() {
        // „50" i „1800" nie mogą być jednocześnie procentami — bierzemy piksele
        val (x, y) = ScreenCoords.pair(50, 1800, w, h)
        assertEquals(50, x)
        assertEquals(1800, y)
    }

    @Test
    fun `zerowe wspolrzedne sa dozwolone tylko jawnie`() {
        // 0,0 to teraz poprawne „lewy górny róg", bo narzędzie odrzuca brak wartości
        val (x, y) = ScreenCoords.pair(0, 0, w, h)
        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test
    fun `wartosci poza ekranem sa przycinane`() {
        val (x, y) = ScreenCoords.pair(99999, -5, w, h)
        assertEquals(w, x)
        assertEquals(0, y)
    }

    @Test
    fun `maly ekran nie zamienia pikseli na procenty`() {
        // przy krótkim boku <= 1000 px „50" zostaje pikselami (np. tablet/okno)
        val (x, y) = ScreenCoords.pair(50, 50, 800, 600)
        assertEquals(50, x)
        assertEquals(50, y)
    }

    @Test
    fun `zerowy wymiar nie wywala funkcji`() {
        assertEquals(0, ScreenCoords.toPx(10, 0, 10, 0))
    }
}
