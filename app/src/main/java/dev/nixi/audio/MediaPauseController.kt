package dev.nixi.audio

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import dev.nixi.notif.NixiNotificationListener
import dev.nixi.util.LogBus
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Powszechne pauzowanie odtwarzania (muzyka, podcast, Spotify, YouTube Music...)
 * na czas rozmowy z NIXI. Po sesji odtwarzanie jest wznowione.
 *
 * Ważne: `MediaSessionManager.getActiveSessions()` wymaga uprawnienia
 * MEDIA_CONTENT_CONTROL **albo** własnej usługi nasłuchu powiadomień.
 * Wcześniej przekazywaliśmy `null`, co kończyło się SecurityException i
 * pauza multimediów nigdy nie działała — teraz podajemy swój komponent,
 * gdy użytkownik włączył dostęp do powiadomień.
 */
object MediaPauseController {

    private lateinit var mms: MediaSessionManager

    /** Kontrolery, które SAMI zatrzymaliśmy (żeby nie wznawiać cudzej pauzy). */
    private val paused = ConcurrentLinkedDeque<Pair<String, MediaController>>()

    private fun context(): Context = dev.nixi.NixiApp.ctx()

    fun ensureInit() {
        if (!::mms.isInitialized) {
            mms = context().getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        }
    }

    /** Czy w ogóle możemy widzieć sesje innych aplikacji. */
    fun canSeeOtherSessions(): Boolean = NixiNotificationListener.isEnabled(context())

    private fun listenerComponent(): ComponentName? =
        if (canSeeOtherSessions()) ComponentName(context(), NixiNotificationListener::class.java) else null

    /** Zatrzymuje aktywne sesje. Zwraca liczbę wstrzymanych. */
    fun pauseAll(): Int {
        ensureInit()
        val component = listenerComponent()
        if (component == null) {
            LogBus.log("media.pause", "brak dostępu do powiadomień — nie widzę sesji multimediów", "warn")
            dev.nixi.NixiState.emit(dev.nixi.NixiState.NixiEvent.MusicState(false))
            return 0
        }
        var n = 0
        val sessions = try {
            mms.getActiveSessions(component) ?: emptyList()
        } catch (t: Throwable) {
            LogBus.log("media.sessions", t.message ?: "?", "warn")
            emptyList()
        }
        for (s in sessions) {
            try {
                if (s.packageName == context().packageName) continue
                val state = s.playbackState?.state ?: continue
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
                    state == PlaybackState.STATE_FAST_FORWARDING
                ) {
                    s.transportControls.pause()
                    paused.addLast(s.packageName to s)
                    n++
                    LogBus.log("media.pause", s.packageName)
                }
            } catch (t: Throwable) {
                LogBus.log("media.pause.err", t.message ?: "?", "warn")
            }
        }
        dev.nixi.NixiState.emit(dev.nixi.NixiState.NixiEvent.MusicState(n > 0))
        return n
    }

    /** Wznawia TYLKO to, co NIXI sama zatrzymała i co wciąż stoi w pauzie. */
    fun resumeAll(): Int {
        var resumed = 0
        while (true) {
            val item = paused.pollFirst() ?: break
            val (pkg, controller) = item
            try {
                val state = controller.playbackState?.state
                // użytkownik sam wznowił? nie ruszamy
                if (state != null && state != PlaybackState.STATE_PAUSED &&
                    state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_NONE
                ) {
                    continue
                }
                controller.transportControls.play()
                resumed++
                LogBus.log("media.resume", pkg)
            } catch (_: Throwable) {
            }
        }
        dev.nixi.NixiState.emit(dev.nixi.NixiState.NixiEvent.MusicState(false))
        return resumed
    }
}
