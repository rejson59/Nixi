package dev.nixi.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.nixi.NixiState
import dev.nixi.audio.MediaPauseController
import dev.nixi.live.LiveSessionService
import dev.nixi.notif.ActionNotifier
import dev.nixi.overlay.ConversationActivity

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
            // sesja już trwa — pokaż okno
            context.startActivity(
                Intent(context, ConversationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        if (manual) NixiState.manualMode.value = true
        MediaPauseController.pauseAll()
        LiveSessionService.start(context, trigger)
        context.startActivity(
            Intent(context, ConversationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
