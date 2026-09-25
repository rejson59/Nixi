package dev.nixi.tools

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.nixi.ui.MainActivity

/** Systemowy dialog uprawnień — bez nowego ekranu w NIXI. */
object PermAsk {

    const val EXTRA = "nixi_ask_perms"

    fun has(perm: String): Boolean {
        val ctx = ToolContext.app
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** true = już jest. false = otworzyłam aplikację z dialogiem systemu. */
    fun ensure(vararg perms: String): Boolean {
        val miss = perms.filter { it.isNotBlank() && !has(it) }
        if (miss.isEmpty()) return true
        val ctx = ToolContext.app
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA, miss.toTypedArray())
        ctx.startActivity(i)
        return false
    }

    fun contacts() = ensure(Manifest.permission.READ_CONTACTS)
    fun sms() = ensure(Manifest.permission.READ_SMS)
    fun location() = ensure(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    fun call() = ensure(Manifest.permission.CALL_PHONE)
}
