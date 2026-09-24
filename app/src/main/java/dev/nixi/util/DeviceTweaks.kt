package dev.nixi.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Ustawienia systemowe, które decydują o tym, czy NIXI przetrwa w tle.
 *
 * Xiaomi / Redmi / POCO (HyperOS, MIUI) agresywnie ubijają aplikacje:
 * bez „Autostart", „Bateria: bez ograniczeń" i zgody na „okna w tle"
 * nasłuch „Hej Nixi" oraz wezwanie rozmowy zostaną zatrzymane po kilku
 * minutach od zgaszenia ekranu. Tu prowadzimy użytkownika dokładnie tam,
 * gdzie trzeba — a gdy ekranu producenta nie ma, otwieramy ustawienia ogólne.
 */
object DeviceTweaks {

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase().let {
            it.contains("xiaomi") || it.contains("redmi") || it.contains("poco")
        } || systemProperty("ro.miui.ui.version.name").isNotBlank()

    private fun systemProperty(name: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getMethod("get", String::class.java)
        (m.invoke(null, name) as? String).orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun open(ctx: Context, intents: List<Intent>): Boolean {
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { ctx.startActivity(i); true }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    fun appDetails(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))

    /** MIUI: Autostart dla NIXI (bez tego usługa nie wstanie po restarcie). */
    fun openAutoStart(ctx: Context): Boolean = open(ctx, buildList {
        add(Intent().setComponent(ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )))
        add(Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT))
        add(appDetails(ctx))
    })

    /** MIUI: bateria „bez ograniczeń" dla NIXI. */
    fun openBatterySaver(ctx: Context): Boolean = open(ctx, buildList {
        add(Intent("miui.intent.action.APP_POWER_SAVER")
            .putExtra("extra_pkgname", ctx.packageName))
        add(Intent().setComponent(ComponentName(
            "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
        )).putExtra("package_name", ctx.packageName)
            .putExtra("package_label", "NIXI"))
        add(ignoreBatteryOptimizations(ctx))
        add(batteryOptimizationList())
    })

    /** MIUI: edytor uprawnień (m.in. „okna w tle" — potrzebne dla kuli nad blokadką). */
    fun openPermissionEditor(ctx: Context): Boolean = open(ctx, buildList {
        add(Intent("miui.intent.action.APP_PERM_EDITOR")
            .putExtra("extra_pkgname", ctx.packageName))
        add(Intent().setComponent(ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.permissions.PermissionsEditorActivity"
        )).putExtra("extra_pkgname", ctx.packageName))
        add(appDetails(ctx))
    })

    /** Bezpośredni systemowy dialog „pozwól działać w tle" (mamy uprawnienie). */
    fun ignoreBatteryOptimizations(ctx: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + ctx.packageName)
        )

    /** Lista wszystkich aplikacji z wyłączoną optymalizacją (fallback). */
    fun batteryOptimizationList(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Throwable) {
        false
    }

    fun isFullScreenIntentAllowed(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = dev.nixi.NixiApp.ctx()
                .getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.canUseFullScreenIntent()
        } else true
    } catch (_: Throwable) {
        false
    }

    /** Skrót dla użytkownika, gdy sprząta w ustawieniach systemowych. */
    fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
}
