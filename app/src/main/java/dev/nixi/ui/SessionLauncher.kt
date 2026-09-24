package dev.nixi.ui

import android.content.Context
import android.content.pm.PackageManager
import dev.nixi.NixiState
import dev.nixi.audio.MediaPauseController
import dev.nixi.live.LiveSessionService
import dev.nixi.notif.ActionNotifier
import kotlinx.coroutines.launch

/** Start sesji głosowej z aplikacji (przycisk "Rozmów" / "Tryb ręczny"). */
object SessionLauncher {

    fun start(context: Context, trigger: String, manual: Boolean = false) {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActionNotifier.notify(
                context, "NIXI: brak mikrofonu",
                "Uznij uprawnienie do mikrofonu, aby rozmawiać głosowo.", short = false
            )
            return
        }
        if (LiveSessionService.running) {
            dev.nixi.overlay.ConversationHost.show(context)
            return
        }
        if (manual) NixiState.manualMode.value = true
        // pauza multimediów to wywołania binderowe — trzymamy je z dala od
        // wątku głównego (ryzyko ANR przy wolnym menedżerze sesji)
        dev.nixi.NixiApp.scope.launch {
            runCatching { MediaPauseController.pauseAll() }
        }
        LiveSessionService.start(context, trigger)
        dev.nixi.overlay.ConversationHost.show(context)
    }
}
