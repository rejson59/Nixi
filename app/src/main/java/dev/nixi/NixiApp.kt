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
        dev.nixi.util.ErrorReport.load()
        // nowy zestaw zasad wykonania (bez pytań głosem) — nie trzymaj starego promptu
        if (!LocalStore.promptStatic.contains("ZASADY WYKONANIA")) {
            LocalStore.promptStaticAt = 0L
        }
        SupabaseHub.init(this)
        ActionNotifier.init(this)
        // narzędzia mogą być wołane z tła (ciche reguły, przypomnienia) —
        // kontekst aplikacji musi być gotowy od pierwszej chwili
        dev.nixi.tools.ToolContext.app = this

        // Błędy globalne -> tabela "errors" (best effort, nigdy nie blokujemy aplikacji)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                LogBus.logException("uncaught", throwable)
                dev.nixi.util.ErrorReport.note("crash", throwable.javaClass.simpleName + ": " + (throwable.message ?: ""))
            } catch (_: Throwable) {
            }
            scope.launch { SupabaseHub.reportError("uncaught_${thread.name}", throwable) }
            previous?.uncaughtException(thread, throwable)
        }

        ActionNotifier.ensureChannels()

        // Detektor „Hej Nixi" po przebudowie (kaskada mel) nie rozumie starego
        // szablonu Goertzla. Sprawdzamy to od razu, żeby użytkownik zobaczył
        // prośbę o ponowne nagranie, a nie ciszę. Bez parsowania JSON-a:
        // nowy format zawsze zawiera wersję 2.
        runCatching {
            val t = LocalStore.wakeTemplates
            if (t.isNotBlank() && !t.contains("\"v\":2")) LocalStore.wakeNeedsEnroll = true
        }

        // Piesek nasłuchu (co 15 min) — HyperOS potrafi ubić usługę, gdy
        // aplikacja jest zamknięta, a wtedy nikt by jej nie podniósł.
        runCatching { dev.nixi.boot.WakeWatchdog.arm(this) }
            .onFailure { dev.nixi.util.LogBus.log("watchdog", it.message ?: "?", "warn") }

        // Struktura bazy: „niech same się utworzą”. Po starcie (spokojnie,
        // żeby nie konkurować o sieć z pierwszymi zapytaniami) sprawdzamy,
        // których tabel brakuje, i próbujemy je założyć — bez pytania
        // użytkownika o cokolwiek, jeśli tylko mamy do tego prawo.
        scope.launch {
            kotlinx.coroutines.delay(3000)
            runCatching {
                if (LocalStore.supabaseUrl.isNotBlank() && LocalStore.supabaseKey.isNotBlank()) {
                    val st = dev.nixi.db.DbProvisioner.check()
                    if (st.missing.isNotEmpty()) {
                        val out = dev.nixi.db.DbProvisioner.provision()
                        dev.nixi.util.LogBus.log(
                            "db.provision",
                            "auto: ${st.summary} → ${out.message}",
                            if (out.ok) "info" else "warn"
                        )
                    }
                }
            }
        }

        // Zaległe zapisy z kolejki offline (pamięć/logi z czasu bez sieci).
        scope.launch {
            runCatching { dev.nixi.db.OfflineQueue.flush(this@NixiApp) }
                .onFailure { dev.nixi.util.LogBus.log("queue.flush", it.message ?: "?", "warn") }
        }

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
