package dev.nixi.screen

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import dev.nixi.notif.ActionNotifier
import dev.nixi.util.LogBus
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Zrzuty ekranu w trybie ręcznym (MediaProjection).
 * Konsent użytkownika: systemowy dialog "Zezwól na nagrywanie ekranu"
 * (uruchamiany przez ConversationActivity, bo konsent wymaga aktywnego okna).
 */
class ScreenCaptureService : Service() {

    companion object {
        const val NOTIF_ID = 1003
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        @Volatile var instance: ScreenCaptureService? = null

        fun isRunning(): Boolean = instance != null

        fun start(ctx: android.content.Context, resultCode: Int, data: Intent): Boolean {
            val intent = Intent(ctx, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            return try {
                ctx.startForegroundService(intent)
                true
            } catch (t: Throwable) {
                LogBus.log("screen.start", "nie mogę wystartować usługi: ${t.message}", "error")
                false
            }
        }

        fun stop(ctx: android.content.Context) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: android.media.ImageReader? = null
    private val size = AtomicReference<Pair<Int, Int>?>(null)
    private val handlerThread = HandlerThread("nixi-screen").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /**
     * Tryb ręczny musi przeżyć obrót telefonu: rozmiar podglądu brany raz przy
     * starcie rozjeżdżał się z ekranem (zrzuty przycięte, kliki obok celu).
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = rebuildDisplay()
    }
    private fun displayManager() =
        getSystemService(DISPLAY_SERVICE) as? DisplayManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data != null && resultCode != 0) {
            initProjection(resultCode, data)
        } else {
            LogBus.log("screen.start", "brak consentu", "warn")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Rozmiar i gęstość ekranu w tej chwili (odporne na obrót). */
    private fun currentMetrics(): Triple<Int, Int, Int> {
        val dm = android.util.DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as? android.view.WindowManager
        if (android.os.Build.VERSION.SDK_INT >= 30 && wm != null) {
            val b = wm.currentWindowMetrics.bounds
            dm.densityDpi = resources.displayMetrics.densityDpi
            return Triple(b.width(), b.height(), dm.densityDpi)
        }
        @Suppress("DEPRECATION")
        val display = wm?.defaultDisplay
        @Suppress("DEPRECATION")
        display?.getRealMetrics(dm)
        val w = if (dm.widthPixels > 0) dm.widthPixels else resources.displayMetrics.widthPixels
        val h = if (dm.heightPixels > 0) dm.heightPixels else resources.displayMetrics.heightPixels
        val dpi = if (dm.densityDpi > 0) dm.densityDpi else resources.displayMetrics.densityDpi
        return Triple(w, h, dpi)
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val (w, h, dpi) = currentMetrics()

        val proj = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            LogBus.log("screen.projection", "błąd: ${t.message}", "error")
            null
        }
        if (proj == null) {
            stopSelf()
            return
        }
        projection = proj
        // Android 14+ wymaga zarejestrowania callbacku PRZED createVirtualDisplay —
        // inaczej system rzuca SecurityException i tryb ręczny nie startuje.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                LogBus.log("screen.projection", "konsent wycofany przez system/użytkownika")
                stopSelf()
            }
        }, handler)
        val reader = android.media.ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        size.set(w to h)
        virtualDisplay = try {
            proj.createVirtualDisplay(
                "NixiScreen", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        } catch (t: Throwable) {
            LogBus.log("screen.projection", "createVirtualDisplay: ${t.message}", "error")
            runCatching { proj.stop() }
            projection = null
            runCatching { reader.close() }
            imageReader = null
            stopSelf()
            return
        }
        LogBus.log("screen.start", "projekcja aktywna ${w}x$h")
        // nasłuch obrotu ekranu (tylko gdy mamy projekcję)
        runCatching { displayManager()?.registerDisplayListener(displayListener, handler) }
    }

    /**
     * Ekran zmienił rozmiar (obrót) — odtwarzamy VirtualDisplay i ImageReader,
     * żeby zrzuty i współrzędne znów zgadzały się z tym, co widzi użytkownik.
     */
    private fun rebuildDisplay() {
        val proj = projection ?: return
        val (w, h, dpi) = currentMetrics()
        if (w <= 0 || h <= 0) return
        if (size.get() == (w to h)) return
        LogBus.log("screen.rotate", "ekran ${w}x$h — odtwarzam podgląd")
        runCatching { virtualDisplay?.setSurface(null) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        val reader = android.media.ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        size.set(w to h)
        // narzędzia (screen_tap/swipe) muszą znać nowy rozmiar
        dev.nixi.tools.ToolContext.screenWidthPx = w
        dev.nixi.tools.ToolContext.screenHeightPx = h
        virtualDisplay = runCatching {
            proj.createVirtualDisplay(
                "NixiScreen", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        }.onFailure {
            LogBus.log("screen.rotate", "nie mogę odtworzyć podglądu: ${it.message}", "warn")
        }.getOrNull()
    }

    fun displaySize(): Pair<Int, Int> = size.get() ?: (0 to 0)

    /** Zrzut ekranu jako JPEG base64 (max szer. [maxWidth]). */
    fun screenshotJpeg(maxWidth: Int = 1152, quality: Int = 62): String? {
        val reader = imageReader ?: return null
        val img = waitImage(reader) ?: return null
        return try {
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val (w, h) = size.get() ?: return null
            val rowPadding = rowStride - pixelStride * w
            val bmpFull = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
            bmpFull.copyPixelsFromBuffer(buffer)
            val bmp = if (rowPadding > 0) Bitmap.createBitmap(bmpFull, 0, 0, w, h) else bmpFull
            var out = bmp
            if (w > maxWidth) {
                val ratio = maxWidth / w.toFloat()
                out = Bitmap.createScaledBitmap(bmp, maxWidth, (h * ratio).toInt(), true)
                if (bmp !== out) bmp.recycle()
            }
            val baos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (t: Throwable) {
            LogBus.log("screen.jpeg", t.message ?: "?", "warn")
            null
        } finally {
            img.close()
        }
    }

    private fun waitImage(reader: android.media.ImageReader): android.media.Image? {
        var i = 0
        while (i < 6) {
            val img = reader.acquireLatestImage()
            if (img != null) return img
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                return null
            }
            i++
        }
        return null
    }

    private fun startForegroundCompat() {
        val notif = ActionNotifier.fgsNotification(
            "NIXI: tryb ręczny", "NIXI widzi Twój ekran i steruje nim.", NOTIF_ID
        )
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        runCatching { displayManager()?.unregisterDisplayListener(displayListener) }
        super.onDestroy()
        // najpierw zwalniamy wątek zadań (ImageReader/VirtualDisplay żyją na handlerze)
        runCatching { handler.removeCallbacksAndMessages(null) }
        if (instance === this) instance = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
        handlerThread.quitSafely()
        LogBus.log("screen.stop", "projekcja wyłączona")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
