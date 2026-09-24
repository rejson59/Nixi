package dev.nixi.util

import kotlin.math.roundToInt

/**
 * Zamiana współrzędnych podanych przez model na piksele ekranu.
 *
 * Model czasem myli jednostki i podaje procenty (0..100) zamiast pikseli.
 * Przyjmujemy procenty TYLKO wtedy, gdy OBJE współrzędne mieszczą się w 0..100,
 * a ekran jest duży — inaczej „50" znaczyłoby 50 px, ale też połowę ekranu,
 * więc klik trafiałby losowo.
 *
 * Logika jest czysta (bez Androida), żeby dała się pokryć testami — ten fragment
 * już raz był źródłem błędu (klik w lewy górny róg przy braku współrzędnych).
 */
object ScreenCoords {

    fun toPx(value: Int, dim: Int, other: Int, otherDim: Int): Int {
        if (dim <= 0) return 0
        val percent = value in 0..100 && other in 0..100 && dim > 1000 && otherDim > 1000
        val px = if (percent) (value / 100f * dim).roundToInt() else value
        return px.coerceIn(0, dim)
    }

    /** Para (x, y) z rozdzielczością ekranu. */
    fun pair(x: Int, y: Int, width: Int, height: Int): Pair<Int, Int> =
        toPx(x, width, y, height) to toPx(y, height, x, width)
}
