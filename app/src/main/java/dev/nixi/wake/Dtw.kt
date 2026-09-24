package dev.nixi.wake

import kotlin.math.max
import kotlin.math.min

/**
 * DTW z pasmem Sakoe-Chiby — dopasowanie czasowe dwóch wypowiedzi.
 *
 * W kaskadzie Google drugi stopień to sieć neuronowa, która „rozciąga" czas
 * za pomocą posteriorów fonemów (dekoder wybiera największy iloczyn
 * wygładzonych posteriorów w rosnącej kolejności jednostek). My nie mamy
 * wytrenowanej sieci ani danych treningowych, więc rolę „elastycznego
 * dopasowania do wzorca" pełni DTW na cechach log-mel — ta sama idea
 * (dopasuj mimo różnic tempa), tylko klasycznym algorytmem.
 */
object Dtw {

    /** Odległość dwóch sekwencji cech (0 = identyczne). */
    fun distance(a: List<FloatArray>, b: List<FloatArray>): Double {
        val la = a.size
        val lb = b.size
        if (la == 0 || lb == 0) return 10.0
        val band = max(6, (0.35 * max(la, lb)).toInt())
        val inf = Double.MAX_VALUE / 2
        var prev = DoubleArray(lb + 1) { inf }
        var curr = DoubleArray(lb + 1) { inf }
        prev[0] = 0.0
        for (i in 1..la) {
            java.util.Arrays.fill(curr, inf)
            val lo = max(1, i - band)
            val hi = min(lb, i + band)
            val ai = a[i - 1]
            for (j in lo..hi) {
                val d = frameDistance(ai, b[j - 1])
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

    fun frameDistance(x: FloatArray, y: FloatArray): Double {
        val n = min(x.size, y.size)
        if (n == 0) return 0.0
        var s = 0.0
        for (i in 0 until n) {
            val d = (x[i] - y[i]).toDouble()
            s += d * d
        }
        return s / n
    }
}
