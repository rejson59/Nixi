package dev.nixi

import android.app.Application
import android.content.Context
import dev.nixi.db.SupabaseHub
import dev.nixi.notif.ActionNotifier
import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NixiApp : Application() {

    companion object {
        lateinit var app: NixiApp
            private set
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fun ctx(): Context = app
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        LocalStore.init(this)
        SupabaseHub.init(this)
        ActionNotifier.init(this)

        // Błędy globalne -> tabela "errors" (best effort, nigdy nie blokujemy aplikacji)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                LogBus.logException("uncaught", throwable)
            } catch (_: Throwable) {
            }
            scope.launch { SupabaseHub.reportError("uncaught_${thread.name}", throwable) }
            previous?.uncaughtException(thread, throwable)
        }

        ActionNotifier.ensureChannels()

        // Diagnostyka audio na ekranie głównym (np. „mikrofon zajęty przez rozmowę”).
        scope.launch {
            dev.nixi.NixiState.events.collect { e ->
                if (e is dev.nixi.NixiState.NixiEvent.ErrorHappened && e.tag == "audio") {
                    dev.nixi.NixiState.lastAudioError.value = e.message
                }
            }
        }
    }
}
