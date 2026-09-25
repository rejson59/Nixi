package dev.nixi.notif

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Zainstalowany dziennik (eduVulcan / Vulcan), bez zgadywania jednego pakietu. */
object DiaryApps {

    fun matches(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val p = pkg.lowercase()
        if (looksLike(p, pkg)) return true
        return installed(context).any { it == pkg }
    }

    fun installed(context: Context): List<String> {
        val pm = context.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launch, 0).mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            val label = ri.loadLabel(pm).toString()
            if (looksLike(pkg.lowercase(), label)) pkg else null
        }.distinct()
    }

    private fun looksLike(pkgLower: String, label: String): Boolean {
        val l = label.lowercase()
        return pkgLower.contains("vulcan") || pkgLower.contains("eduvulcan") ||
            pkgLower.contains("uonet") || l.contains("eduvulcan") ||
            l.contains("vulcan") || (l.contains("dziennik") && l.contains("vulcan"))
    }
}
