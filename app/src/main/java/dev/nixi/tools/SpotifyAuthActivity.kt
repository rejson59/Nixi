package dev.nixi.tools

import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.nixi.notif.ActionNotifier
import dev.nixi.util.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Odbiera redirect `nixi://spotify-auth` po logowaniu Spotify (flow PKCE). */
class SpotifyAuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent?.data?.toString())
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent.data?.toString())
    }

    private fun handle(url: String?) {
        if (url == null || !url.startsWith("nixi://spotify-auth")) {
            finish()
            return
        }
        val uri = android.net.Uri.parse(url)
        val code = uri.getQueryParameter("code")
        val err = uri.getQueryParameter("error")
        when {
            code != null -> CoroutineScope(Dispatchers.Main).launch {
                val msg = SpotifyApi.exchangeCode(code)
                ActionNotifier.notify(applicationContext, "NIXI: Spotify", msg, short = false)
                finish()
            }
            err != null -> {
                ActionNotifier.notify(
                    applicationContext, "NIXI: Spotify", "Logowanie przerwane: $err", short = false
                )
                LogBus.log("spotify.auth", "error=$err", "warn")
                finish()
            }
            else -> finish()
        }
    }
}
