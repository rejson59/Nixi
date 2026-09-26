package dev.nixi.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.nixi.NixiApp
import dev.nixi.NixiState
import dev.nixi.live.LiveSessionService
import dev.nixi.util.LogBus

/**
 * Pigułka NIXI jako prawdziwy overlay (WindowManager), nie pełny ekran.
 * Aplikacja pod spodem zostaje wznowiona i przyjmuje kliknięcia wszędzie
 * poza samą pigułką.
 */
object ConversationHud {

    @Volatile private var view: ComposeView? = null
    @Volatile private var owner: HudOwner? = null

    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = view != null

    @Synchronized
    fun show(context: Context) {
        if (view != null) return
        if (!canShow(context)) return
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            title = "NIXI"
        }
        val host = HudOwner().also { it.start() }
        val cv = ComposeView(app).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent {
                ConversationUi(
                    onClose = {
                        LiveSessionService.stop(NixiApp.ctx(), "użytkownik zamknął okno")
                        hide()
                    },
                    onManualMode = {
                        // zgoda MediaProjection wymaga Activity — NIE druga pigułka
                        runCatching {
                            app.startActivity(
                                Intent(app, ConversationActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    .putExtra("ask_projection", true)
                                    .putExtra("projection_only", true)
                            )
                        }
                    },
                    enableAutoManual = true,
                    onOpenApp = {
                        runCatching {
                            app.startActivity(
                                Intent(app, dev.nixi.ui.MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                )
            }
        }
        try {
            wm.addView(cv, lp)
            view = cv
            owner = host
            LogBus.log("hud", "pigułka overlay")
        } catch (t: Throwable) {
            host.stop()
            LogBus.log("hud", "nie mogę pokazać overlay: ${t.message}", "warn")
        }
    }

    @Synchronized
    fun hide() {
        val v = view ?: return
        view = null
        val host = owner
        owner = null
        runCatching {
            val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
        }
        host?.stop()
    }

    private class HudOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val life = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val saved = SavedStateRegistryController.create(this)
        init {
            saved.performAttach()
            saved.performRestore(null)
        }
        fun start() {
            life.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            life.handleLifecycleEvent(Lifecycle.Event.ON_START)
            life.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun stop() {
            runCatching { life.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
            runCatching { life.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
            runCatching { life.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
            store.clear()
        }
        override val lifecycle: Lifecycle get() = life
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    }
}

/** Jedno miejsce: overlay jeśli można, inaczej cienka aktywność. */
object ConversationHost {
    fun show(context: Context) {
        if (ConversationHud.isShowing()) return
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val locked = km?.isKeyguardLocked == true
        if (!locked && ConversationHud.canShow(context)) {
            ConversationHud.show(context)
            return
        }
        runCatching {
            context.startActivity(
                Intent(context, ConversationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun hide() {
        ConversationHud.hide()
    }
}
