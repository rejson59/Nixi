package dev.nixi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wspólny stan aplikacji (jedna instancja procesu).
 * UI, usługi i narzędzia czytają/zapisują te flify, więc okno rozmowy
 * może biec nad blokadką, a wake-word w tle — bez dodatkowych IPC.
 */
object NixiState {

    enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, MANUAL, TPM_LIMIT, ERROR }

    /** Stan kuli (nagrywanie / myślenie / mówienie / tryb ręczny). */
    val orbState = MutableStateFlow(OrbState.IDLE)
    val orb: StateFlow<OrbState> = orbState.asStateFlow()

    /** Poziom mikrofonu 0..1 (do skali kuli, gdy NIXI nasłuchuje). */
    val micLevel = MutableStateFlow(0f)

    /** Poziom głośności głosu NIXI 0..1 (głośniej => większa kula). */
    val speakLevel = MutableStateFlow(0f)

    /** Czy nasłuch "Hej Nixi" jest aktywny. */
    val wakeActive = MutableStateFlow(false)

    /** Czy trwa sesja głosowa. */
    val inSession = MutableStateFlow(false)

    /** Reset bezczynności z narzędzia session/later. */
    @Volatile var sessionKeepAliveAt: Long = 0L

    /** Czy aktywny jest tryb ręczny (ekran). */
    val manualMode = MutableStateFlow(false)

    /**
     * W tej rozmowie użytkownik już potwierdził destrukcyjną akcję —
     * kolejne usunięcia idą bez okna (dopóki sesja trwa).
     */
    val sessionTrusted = MutableStateFlow(false)

    /** Model poprosił o podgląd ekranu — okno rozmowy pokaże dialog systemowy RAZ. */
    val wantScreenCapture = MutableStateFlow(false)

    /** Akcje oczekujące na potwierdzenie użytkownika (np. usuwanie). */
    data class PendingAction(
        val id: String,
        val title: String,
        val detail: String,
        val icon: String, // nazwa narzędzia
    )
    val pendingActions = MutableStateFlow<List<PendingAction>>(emptyList())

    /** Propozycja DDL (nowa tabela/kolumna) do przeglądu na ekranie Tabele. */
    val pendingSql = MutableStateFlow("")

    /** Zużycie limitu tokenów (TPM) w bieżącej sesji — pokazywane na ekranie głównym. */
    data class TpmInfo(
        val used: Int = 0,
        val limit: Int = 0,
        val percent: Int = 0,
        val backoffSec: Long = 0,
    )

    val tpm = MutableStateFlow(TpmInfo())

    /** Ostatni błąd mikrofonu/audio (diagnostyka na ekranie głównym). */
    val lastAudioError = MutableStateFlow("")

    /**
     * Ostatni błąd połączenia z Gemini (kod + treść od serwera).
     * Pusty = ostatnia próba była w porządku. Pokazujemy go na ekranie
     * głównym — bez tego „NIXI nie odpowiada” nie mówiło, co się stało.
     */
    val lastSessionError = MutableStateFlow("")

    /** Krótki komunikat po starcie (kopia ustawień / niska bateria). */
    val configHint = MutableStateFlow("")

    /**
     * Wynik samokontroli („Sprawdź NIXI”): opis krok po kroku, co działa,
     * a co nie — klucz API, model, mikrofon, Supabase.
     */
    val selfCheck = MutableStateFlow<List<dev.nixi.util.SelfCheck.Item>>(emptyList())

    /** True, gdy samokontrola właśnie trwa. */
    val selfCheckRunning = MutableStateFlow(false)

    sealed interface NixiEvent {
        data class ToolStarted(val name: String, val args: String) : NixiEvent
        data class ToolDone(val name: String, val ok: Boolean, val summary: String) : NixiEvent
        data class SessionStarted(val trigger: String) : NixiEvent
        data class SessionEnded(val reason: String, val durationSec: Long) : NixiEvent
        data class TpmLimited(val message: String) : NixiEvent
        data class ErrorHappened(val tag: String, val message: String) : NixiEvent
        object ManualModeReady : NixiEvent
        object ManualModeEnded : NixiEvent
        data class MusicState(val paused: Boolean) : NixiEvent
    }

    private val _events = MutableSharedFlow<NixiEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NixiEvent> = _events.asSharedFlow()

    fun emit(event: NixiEvent) {
        _events.tryEmit(event)
    }

    /** Podgląd ostatniego zdarzenia narzędzia (do chipa w oknie rozmowy). */
    val lastToolLine = MutableStateFlow("")

    /** Ostatnie usłyszane / powiedziane zdanie (pigułka). */
    val lastHeard = MutableStateFlow("")
    val lastSaid = MutableStateFlow("")

    /** Kto wywołał ostatnią sesję („wake” lub „button”) + czas trafienia. */
    @Volatile var wakeTrigger = "wake"
    @Volatile var wakeHitAt = 0L
}
