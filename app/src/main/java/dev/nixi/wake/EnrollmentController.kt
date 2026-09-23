package dev.nixi.wake

import dev.nixi.audio.AudioBus
import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Rejestrowanie "Hej Nixi" (3 próby po 2 s) podczas onboardingu / w ustawieniach.
 * Po 3. próbie kalibruje próg i zapisuje szablon lokalnie.
 */
object EnrollmentController {

    data class State(
        val takes: Int = 0,
        val recording: Boolean = false,
        val message: String = "",
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var recordJob: Job? = null
    private val attempts = mutableListOf<List<FloatArray>>()

    fun reset() {
        attempts.clear()
        recordJob?.cancel()
        _state.value = State(message = if (LocalStore.wakeTemplates.isNotBlank()) "Wcześniejszy szablon istnieje — nadpisać 3 nowymi próbami?" : "Kliknij i powiedz „Hej Nixi”.")
    }

    fun startAttempt() {
        val s = _state.value
        if (s.recording) {
            finishAttempt()
            return
        }
        if (s.done) {
            // nowa seria rejestracji
            attempts.clear()
            _state.value = State(message = "Nowa seria: kliknij i powiedz „Hej Nixi”.")
            return
        }
        lastLen = 0
        _state.value = s.copy(recording = true, message = "Mów teraz: „Hej Nixi” (2 s)…")
        val consumer = object : AudioBus.Consumer {
            override fun onPcm(pcm: ShortArray, l: Int) {
                if (lastLen < lastBuffer.size) {
                    val take = minOf(l, lastBuffer.size - lastLen)
                    System.arraycopy(pcm, 0, lastBuffer, lastLen, take)
                    lastLen += take
                }
            }
        }
        AudioBus.setConsumer(consumer)
        if (!AudioBus.isRunning()) AudioBus.start(consumer)
        recordJob = scope.launch {
            delay(2000)
            finishAttempt()
        }
    }

    private fun finishAttempt() {
        recordJob?.cancel()
        AudioBus.stop()
        // wznowienie nasłuchu (jeśli działa usługa)
        if (WakeWordService.running) {
            WakeWordService.restoreWakeConsumer()
        }
        val s = _state.value
        if (!s.recording) return

        val frames = WakeEnroll.processAttempt(lastBuffer, lastLen)
        _state.value = s.copy(recording = false)
        if (frames.ok) {
            attempts.add(frames.frames)
            val takes = attempts.size
            if (takes >= 3) {
                val threshold = WakeEnroll.calibrate(attempts)
                val json = WakeEngine().toJson(16, 10, threshold, attempts)
                LocalStore.wakeTemplates = json
                WakeWordService.engine.loadFromJson(json)
                LogBus.log("wake.enroll", "szablon zapisany (próg ${"%.3f".format(threshold)})")
                _state.value = State(
                    takes = takes, recording = false,
                    message = "Gotowe! NIXI poznała Twoje „Hej Nixi”.", done = true
                )
            } else {
                _state.value = State(
                    takes = takes, recording = false,
                    message = "Próba ${takes}/3 — dobrze! Jeszcze ${3 - takes}."
                )
            }
        } else {
            _state.value = s.copy(
                recording = false,
                message = "Nie usłyszałam mowy (${frames.note}). Spróbuj jeszcze raz."
            )
        }
    }

    private val lastBuffer = ShortArray(16000 * 2)
    private var lastLen = 0
}
