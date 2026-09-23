package dev.nixi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import dev.nixi.NixiState
import kotlin.math.PI
import kotlin.math.sin

/**
 * KULA NIXI — port "Pulse Engine / Orb Forge" (CSS) na Compose Canvas.
 * Warstwy: halo, korpus, dwie energiczne warstwy conic (screen blend),
 * rdzeń, pasek shimmer, rim, refleks.
 *
 * Animacje żyją ze stanem:
 *  - IDLE     : spokojny oddech (4.2 s),
 *  - LISTENING: szybszy puls halo,
 *  - SPEAKING : skala = 1 + 0.28 * głośność głosu (głośniej => większa kula),
 *  - MANUAL   : fioletowo-czerwony odcień akcentu,
 *  - TPM_LIMIT: przygaszona,
 *  - ERROR    : czerwona poświata.
 */
@Composable
fun NixiOrb(
    size: Dp,
    state: NixiState.OrbState = NixiState.OrbState.IDLE,
    level: Float = 0f,
    modifier: Modifier = Modifier,
) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = System.nanoTime()
        while (true) {
            kotlinx.coroutines.delay(33)
            val now = System.nanoTime()
            t += (now - last) / 1e9f
            last = now
        }
    }

    val pulsePeriod = when (state) {
        NixiState.OrbState.LISTENING -> 1.2f
        NixiState.OrbState.SPEAKING -> 0.9f
        NixiState.OrbState.TPM_LIMIT -> 3.5f
        else -> 1.9f
    }
    val breath = (1f + 0.045f * (sin(t * 2f * PI / 4.2f) + 1f) * 0.5f).toFloat()
    val pulse = ((sin(t * 2f * PI / pulsePeriod + PI / 3f) + 1f) * 0.5f).toFloat()
    val levelBoost = when (state) {
        NixiState.OrbState.SPEAKING -> 0.30f
        NixiState.OrbState.LISTENING -> 0.18f
        else -> 0.0f
    }
    val scale = breath * (1f + levelBoost * level.coerceIn(0f, 1f))
    val haloAlpha = 0.55f + 0.35f * pulse
    val accent = when (state) {
        NixiState.OrbState.MANUAL -> Color(0xFF8F6FFF)
        NixiState.OrbState.ERROR -> Color(0xFFFF5A5A)
        NixiState.OrbState.TPM_LIMIT -> Color(0xFF8F6FFF)
        else -> Color(0xFF8F6FFF)
    }

    Canvas(modifier = modifier.size(size)) {
        val c = this.size
        val cx = c.width / 2f
        val cy = c.height / 2f
        val r = minOf(c.width, c.height) / 2f * scale * 0.62f
        if (r <= 1f) return@Canvas

        // ── HALO ─────────────────────────────────────────────
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.75f * haloAlpha),
                    accent.copy(alpha = 0.30f * haloAlpha),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = r * 1.55f * (1f + 0.07f * pulse),
            ),
            radius = r * 1.55f * (1f + 0.07f * pulse),
            center = Offset(cx, cy),
        )

        // ── KORPUS ───────────────────────────────────────────
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFE0D6FF), Color(0xFF7A5CFF), Color(0xFF1A0066)),
                center = Offset(cx - r * 0.20f, cy - r * 0.28f),
                radius = r * 1.9f,
            ),
            radius = r,
            center = Offset(cx, cy),
        )
        // wewnętrzna poświata fioletowa (dołem)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, accent.copy(alpha = 0.35f)),
                center = Offset(cx, cy + r * 0.2f),
                radius = r,
            ),
            radius = r,
            center = Offset(cx, cy),
        )

        // ── ENERGIA 1 (conic, obrót 9 s) ─────────────────────
        val energySpeed = if (state == NixiState.OrbState.SPEAKING) 1.6f else 1f
        val rot1 = (t * 40f * energySpeed) % 360f
        rotate(rot1, pivot = Offset(cx, cy)) {
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0x8CEFE9FF),
                    Color.Transparent,
                    accent.copy(alpha = 0.4f),
                    Color.Transparent,
                    Color(0x4CEFE9FF),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
            ),
            radius = r,
            center = Offset(cx, cy),
            alpha = 0.85f,
            blendMode = BlendMode.Screen,
        )
        }

        // ── ENERGIA 2 (odwrotnie, wolniej) ────────────────────
        val rot2 = -((t * 21f) % 360f)
        rotate(rot2, pivot = Offset(cx, cy)) {
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color.Transparent,
                    accent.copy(alpha = 0.45f),
                    Color.Transparent,
                    Color(0x59EFE9FF),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
            ),
            radius = r,
            center = Offset(cx, cy),
            alpha = 0.7f,
            blendMode = BlendMode.Screen,
        )
        }

        // ── RDZEŃ ────────────────────────────────────────────
        val coreScale = 1f + 0.2f * pulse + 0.25f * level
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFF2EFFF).copy(alpha = 0.95f),
                    Color(0x73EFE9FF),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = r * 0.38f * coreScale,
            ),
            radius = r * 0.38f * coreScale,
            center = Offset(cx, cy),
        )

        // ── PASEK SHIMMER ────────────────────────────────────
        val shimmerPhase = (t / 6f) % 1f
        val shimmerX = -0.75f + 2.1f * shimmerPhase
        drawShimmerBand(cx, cy, r, shimmerX)

        // ── RIM ──────────────────────────────────────────────
        drawCircle(
            color = Color.White.copy(alpha = 0.15f),
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color(0x4D8F6FFF)),
                center = Offset(cx, cy),
                radius = r,
            ),
            radius = r,
            center = Offset(cx, cy),
        )

        // ── REFLEKSY ─────────────────────────────────────────
        drawSpecular(
            center = Offset(cx - r * 0.34f, cy - r * 0.44f),
            size = Size(r * 0.62f, r * 0.40f),
            alpha = 0.5f,
        )
        drawSpecular(
            center = Offset(cx - r * 0.24f, cy - r * 0.34f),
            size = Size(r * 0.20f, r * 0.13f),
            alpha = 0.52f,
        )
        // fioletowy refleks dół-prawo
        drawSpecular(
            center = Offset(cx + r * 0.28f, cy + r * 0.38f),
            size = Size(r * 0.55f, r * 0.35f),
            alpha = 0.28f,
            color = accent,
        )
    }
}

private fun DrawScope.drawShimmerBand(cx: Float, cy: Float, r: Float, x: Float) {
    // pasek po przekątnej, przycięty do kuli
    clipRect(left = cx - r, top = cy - r, right = cx + r, bottom = cy + r) {
    rotate(16f, Offset(cx, cy)) {
        val w = r * 0.46f
        val left = cx - r * 1.25f + 2.5f * r * x
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, Color(0x4DFFFFFF), Color.Transparent),
                start = Offset(left, cy - r * 1.4f),
                end = Offset(left + w, cy + r * 1.4f),
            ),
            topLeft = Offset(left, cy - r * 1.4f),
            size = Size(w, r * 2.8f),
            alpha = if (x in 0f..1f) 1f else 0f,
        )
    }
    }
}

private fun DrawScope.drawSpecular(
    center: Offset,
    size: Size,
    alpha: Float,
    color: Color = Color.White,
) {
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = alpha),
                color.copy(alpha = alpha * 0.35f),
                Color.Transparent,
            ),
            center = center,
            radius = maxOf(size.width, size.height) * 0.7f,
        ),
        topLeft = Offset(center.x - size.width / 2f, center.y - size.height / 2f),
        size = size,
    )
}
