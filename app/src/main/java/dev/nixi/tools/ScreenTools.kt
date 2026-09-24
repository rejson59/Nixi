package dev.nixi.tools

import android.accessibilityservice.AccessibilityService
import dev.nixi.NixiState
import kotlinx.coroutines.launch
import dev.nixi.accessibility.NixiAccessibilityService
import dev.nixi.screen.ScreenCaptureService
import dev.nixi.util.LogBus
import dev.nixi.util.ScreenCoords

/**
 * TRYB RĘCZNY — NIXI widzi ekran (zrzuty) i steruje nim (tap/swipe/tekst),
 * dopóki nie wykona zadania. Start wymaga zgody (MediaProjection) —
 * okno rozmowy prosi o nią, gdy model wywołuje screen_manual_start.
 */
object ScreenTools {

    fun manualStart(): ToolResult {
        val svc = ScreenCaptureService.instance
        return if (svc != null) {
            NixiState.manualMode.value = true
            NixiState.wantScreenCapture.value = false
            ToolContext.screenWidthPx = svc.displaySize().first
            ToolContext.screenHeightPx = svc.displaySize().second
            NixiAppScope.onManualModeReady()
            ToolResult.ok(
                "Tryb ręczny aktywny. Widać ekran ${ToolContext.screenWidthPx}x${ToolContext.screenHeightPx}. " +
                    "Używaj screen_get, screen_tap, screen_swipe, screen_text. Kończ przez screen_manual_stop."
            )
        } else {
            // Nie ustawiamy manualMode z góry — to odpalało dialog nagrania ekranu
            // przy KAŻDYM wywołaniu narzędzia, nawet gdy zgoda już leciała.
            NixiState.wantScreenCapture.value = true
            ToolResult.ok(
                "Czekam na jednorazową zgodę na podgląd ekranu (systemowy dialog). " +
                    "Jak użytkownik zatwierdzi, powtórz screen_manual_start. " +
                    "Nie pytaj o to głosem."
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
        if (!NixiAccessibilityService.isAvailable()) {
            return ToolResult.fail(
                "Usługa dostępności NIXI jest wyłączona, więc nie mogę klikać. " +
                    "Poproś użytkownika, aby włączył ją w Ustawienia → Dostępność → NIXI."
            )
        }
        val svc = ScreenCaptureService.instance
            ?: return ToolResult.fail(
                "Brak podglądu ekranu (użytkownik nie dał jeszcze zgody). " +
                    "Uruchom screen_manual_start i poproś o zgodę na podgląd ekranu."
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
        if (w <= 0 || h <= 0) {
            return ToolResult.fail("Nie znam rozdzielczości ekranu — najpierw screen_get.")
        }
        if (x < 0 || y < 0) {
            // brak współrzędnych w wywołaniu nie może kończyć się kliknięciem (0,0)
            return ToolResult.fail("Brak współrzędnych — podaj x i y.")
        }
        val (xPx, yPx) = ScreenCoords.pair(x, y, w, h)
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
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            return ToolResult.fail("Brak współrzędnych — podaj x1, y1, x2, y2.")
        }
        val (w, h) = ToolContext.screenWidthPx to ToolContext.screenHeightPx
        // te same zasady co w screen_tap: małe liczby = procenty
        val (ax, ay) = ScreenCoords.pair(x1, y1, w, h)
        val (bx, by) = ScreenCoords.pair(x2, y2, w, h)
        val ok = svc.swipe(ax.toFloat(), ay.toFloat(), bx.toFloat(), by.toFloat(),
            durationMs.toLong().coerceIn(80, 1500))
        LogBus.log("screen.swipe", "$ax,$ay -> $bx,$by")
        return if (ok) ToolResult.ok("Przesunięto.")
        else ToolResult.fail("Gest nie przeszedł (inny gest w trakcie albo brak usługi).")
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
