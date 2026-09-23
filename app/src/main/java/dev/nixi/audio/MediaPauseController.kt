package dev.nixi.audio

import android.content.Context
import android.media.MediaController
import android.media.MediaSessionManager
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
                    val c = MediaController(s)
                    val ok = if (android.os.Build.VERSION.SDK_INT >= 29) {
                        c.connect(400, TimeUnit.MILLISECONDS)
                    } else {
                        c.connect()
                    }
                    if (ok) {
                        val state = c.playbackState?.state
                        if (state != MediaController.PLAYBACK_STATE_STOPPED) {
                            c.pause()
                            synchronized(lock) { pausedControllers.add(c) }
                            n++
                            LogBus.log("media.pause", s.packageName)
                        }
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
    @Suppress("DEPRECATION")
    private fun activeSessionsCrossApp(): List<MediaSessionManager.ActiveSessions> {
        val list = mutableListOf<MediaSessionManager.ActiveSessions>()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val mediaPackages = listOf(
                "com.spotify.music",
                "com.google.android.apps.youtube.music",
                "com.google.android.apps.youtube.music.beta",
                "com.google.android.apps.youtube.podcasts",
                "com.soundcloud.android",
                "com.google.android.apps.podcasts",
                "org.videolan.vlc",
                "com.pocketcasts.android",
                "com.tunein.player",
            )
            for (pkg in mediaPackages) {
                try {
                    val sessions = mms.getActiveSessions(android.content.pm.PackageIdentifier(pkg, 0))
                    list.addAll(sessions)
                } catch (_: Throwable) {
                }
            }
        } else {
            list.addAll(mms.activeSessions ?: emptyList())
        }
        return list
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
                c.play()
                c.release()
            } catch (t: Throwable) {
                runCatching { c.release() }
            }
        }
        dev.nixi.NixiState.emit(dev.nixi.NixiState.NixiEvent.MusicState(false))
    }
}
