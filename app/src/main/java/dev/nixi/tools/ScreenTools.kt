package dev.nixi.tools

import android.accessibilityservice.AccessibilityService
import dev.nixi.NixiState
import kotlinx.coroutines.launch
import dev.nixi.accessibility.NixiAccessibilityService
import dev.nixi.screen.ScreenCaptureService
import dev.nixi.util.LogBus

/**
 * TRYB RĘCZNY — NIXI widzi ekran (zrzuty) i steruje nim (tap/swipe/tekst),
 * dopóki nie wykona zadania. Start wymaga zgody (MediaProjection) —
 * okno rozmowy prosi o nią, gdy model wywołuje screen_manual_start.
 */
object ScreenTools {

    fun manualStart(): ToolResult {
        NixiState.manualMode.value = true
        val svc = ScreenCaptureService.instance
        return if (svc != null) {
            ToolContext.screenWidthPx = svc.displaySize().first
            ToolContext.screenHeightPx = svc.displaySize().second
            NixiAppScope.onManualModeReady()
            ToolResult.ok(
                "Tryb ręczny aktywny. Widać ekran ${ToolContext.screenWidthPx}x${ToolContext.screenHeightPx}. " +
                    "Używaj screen_get, screen_tap, screen_swipe, screen_text. Kończ przez screen_manual_stop."
            )
        } else {
            ToolResult.ok(
                "Poprosiłam użytkownika o zezwolenie na podgląd ekranu — czekam na zgodę. " +
                    "Gdy ją da, powtórz screen_manual_start."
            )
        }
    }

    fun manualStop(): ToolResult {
        NixiState.manualMode.value = false
        ScreenCaptureService.stop(ToolContext.app)
        NixiAppScope.onManualModeEnded()
        return ToolResult.ok("Tryb ręczny wyłączony.")
    }

    fun getScreen(): ToolResult {
        val svc = ScreenCaptureService.instance
            ?: return ToolResult.fail(
                "Brak podglądu ekranu. Uruchom screen_manual_start i poproś o zgodę."
            )
        val jpeg = svc.screenshotJpeg()
            ?: return ToolResult.fail("Nie udało się pobrać zrzutu ekranu.")
        return ToolResult.ok(
            "Zdjęcie ekranu wysłane do Twojego widzenia. ${ToolContext.screenWidthPx}x${ToolContext.screenHeightPx}.",
            imageB64 = jpeg
        )
    }

    fun tap(x: Int, y: Int): ToolResult {
        val (w, h) = ToolContext.screenWidthPx to ToolContext.screenHeightPx
        val xPx = if (x in 0..100 && w > 1000) (x / 100f * w).toInt() else x
        val yPx = if (y in 0..100 && h > 1000) (y / 100f * h).toInt() else y
        val ok = NixiAccessibilityService.instance?.tap(xPx.toFloat(), yPx.toFloat()) ?: false
        if (!ok) {
            return ToolResult.fail(
                "Brak dostępu do sterowania (Accessibility wyłączony). Zgłoszę to użytkownikowi."
            )
        }
        LogBus.log("screen.tap", "$xPx,$yPx")
        return ToolResult.ok("Kliknięto ($xPx, $yPx).")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): ToolResult {
        val svc = NixiAccessibilityService.instance
            ?: return ToolResult.fail("Brak sterowania (Accessibility wyłączony).")
        val ok = svc.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(),
            durationMs.toLong().coerceIn(80, 1500))
        LogBus.log("screen.swipe", "$x1,$y1 -> $x2,$y2")
        return if (ok) ToolResult.ok("Przesunięto.") else ToolResult.fail("Gest nie przeszedł.")
    }

    fun type(text: String): ToolResult {
        val svc = NixiAccessibilityService.instance
            ?: return ToolResult.fail("Brak sterowania (Accessibility wyłączony).")
        val ok = svc.typeText(text)
        return if (ok) ToolResult.ok("Wpisano tekst.")
        else ToolResult.fail("Brak aktywnego pola tekstowego, do którego mogę pisać.")
    }

    fun back(): ToolResult = navigate(AccessibilityService.GLOBAL_ACTION_BACK, "Wrócono (back).")
    fun home(): ToolResult = navigate(AccessibilityService.GLOBAL_ACTION_HOME, "Otwarto ekran główny.")
    fun recents(): ToolResult = navigate(AccessibilityService.GLOBAL_ACTION_RECENTS, "Pokazano ostatnie aplikacje.")

    private fun navigate(action: Int, okMsg: String): ToolResult {
        val svc = NixiAccessibilityService.instance
            ?: return ToolResult.fail("Brak sterowania (Accessibility wyłączony).")
        val ok = svc.global(action)
        return if (ok) ToolResult.ok(okMsg) else ToolResult.fail("Akcja systemowa nie przeszła.")
    }
}

/** Most do NixiState / tła, żeby tools/ nie importowało UI-flowów. */
internal object NixiAppScope {
    fun onManualModeReady() {
        NixiState.emit(NixiState.NixiEvent.ManualModeReady)
    }

    fun onManualModeEnded() {
        NixiState.emit(NixiState.NixiEvent.ManualModeEnded)
    }

    fun kickoff(block: suspend () -> Unit) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).let { scope ->
            scope.launch { block() }
        }
    }
}
