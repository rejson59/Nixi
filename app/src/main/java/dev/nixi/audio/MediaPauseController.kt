package dev.nixi.audio

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import dev.nixi.util.LogBus
import java.util.concurrent.TimeUnit

/**
 * Powszechne pauzowanie odtwarzania (muzyka, podcast, Spotify, YouTube Music...)
 * na czas rozmowy z NIXI. Po sesji odtwarzanie jest wznowione.
 * Best-effort: jeśli system nie udostępni sesji, NIXI po prostu nie ruszy muzyki.
 */
object MediaPauseController {

    private lateinit var mms: MediaSessionManager
    private val pausedControllers = mutableListOf<MediaController>()
    private val lock = Any()

    private fun context(): Context = dev.nixi.NixiApp.ctx()

    fun ensureInit() {
        if (!::mms.isInitialized) {
            mms = context().getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        }
    }

    /** Zatrzymuje aktywne sesje. Zwraca liczbę wstrzymanych. */
    @Suppress("DEPRECATION")
    fun pauseAll(): Int {
        ensureInit()
        return try {
            val sessions = activeSessionsCrossApp()
            var n = 0
            for (s in sessions) {
                if (s.packageName == context().packageName) continue
                try {
                    val state = s.playbackState?.state
                    if (state != null &&
                        state != android.media.session.PlaybackState.STATE_PAUSED &&
                        state != android.media.session.PlaybackState.STATE_STOPPED
                    ) {
                        s.transportControls.pause()
                        synchronized(lock) { pausedControllers.add(s) }
                        n++
                        LogBus.log("media.pause", s.packageName)
                    }
                } catch (t: Throwable) {
                    LogBus.log("media.pause.err", t.message ?: "?", "warn")
                }
            }
            n
        } catch (t: Throwable) {
            LogBus.log("media.pause.err", t.message ?: "?", "warn")
            0
        }.also {
            dev.nixi.NixiState.emit(dev.nixi.NixiState.NixiEvent.MusicState(it > 0))
        }
    }

    /**
     * Sesje innych aplikacji. API 34+: system ogranicza widoczność,
     * więc pytamy o popularne pakiety multimedialne osobno.
     */
    private fun activeSessionsCrossApp(): List<MediaController> {
        // Bez uprawnienia do powiadomien system nie pokaze sesji innych aplikacji.
        return try {
            mms.getActiveSessions(null) ?: emptyList()
        } catch (t: Throwable) {
            LogBus.log("media.sessions", t.message ?: "?", "warn")
            emptyList()
        }
    }

    /** Wznawia to, co NIXI zatrzymała. */
    fun resumeAll() {
        val list: List<MediaController> = synchronized(lock) {
            val l = pausedControllers.toList()
            pausedControllers.clear()
            l
        }
        for (c in list) {
            try {
                c.transportControls.play()
            } catch (_: Throwable) {
            }
        }
        dev.nixi.NixiState.emit(dev.nixi.NixiState.NixiEvent.MusicState(false))
    }
}
