package dev.nixi.store

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.util.LogBus
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Klucze i ustawienia: lokalny plik + Pobrane + wiersz w `settings`.
 * Po reinstalacji APK odtwarzamy z Pobranych; po nowym telefonie — z Supabase
 * (jak tylko wkleisz URL i klucz anon). Tabele aktualizujemy przy każdej
 * nowej wersji aplikacji.
 */
object ConfigSync {

    private const val FILE = "nixi-config.json"
    private const val SETTINGS_KEY = "nixi_config"

    fun localFile(ctx: Context): File = File(ctx.filesDir, FILE)

    fun snapshot(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("gemini_key", LocalStore.geminiKey)
        put("gemini_model", LocalStore.geminiModel)
        put("memory_model", LocalStore.memoryModel)
        put("supabase_url", LocalStore.supabaseUrl)
        put("supabase_key", LocalStore.supabaseKey)
        put("supabase_pat", LocalStore.supabasePat)
        put("voice_name", LocalStore.voiceName)
        put("tpm_mode", LocalStore.tpmMode)
        put("spotify_client_id", LocalStore.spotifyClientId)
        put("onboarded", LocalStore.onboarded)
        put("wake_enabled", LocalStore.wakeEnabled)
        put("wake_templates", LocalStore.wakeTemplates)
        put("eco_mode", LocalStore.ecoMode)
        put("trust_deletes", LocalStore.trustDeletes)
    }

    fun apply(o: JSONObject) {
        fun s(k: String) = o.optString(k)
        if (s("gemini_key").isNotBlank() && LocalStore.geminiKey.isBlank()) {
            LocalStore.geminiKey = s("gemini_key")
        }
        if (s("supabase_url").startsWith("http") && LocalStore.supabaseUrl.isBlank()) {
            LocalStore.supabaseUrl = s("supabase_url")
        }
        if (s("supabase_key").isNotBlank() && LocalStore.supabaseKey.isBlank()) {
            LocalStore.supabaseKey = s("supabase_key")
        }
        if (s("supabase_pat").isNotBlank() && LocalStore.supabasePat.isBlank()) {
            LocalStore.supabasePat = s("supabase_pat")
        }
        if (s("gemini_model").isNotBlank()) LocalStore.geminiModel = s("gemini_model")
        if (s("memory_model").isNotBlank()) LocalStore.memoryModel = s("memory_model")
        if (s("voice_name").isNotBlank()) LocalStore.voiceName = s("voice_name")
        if (s("tpm_mode").isNotBlank()) LocalStore.tpmMode = s("tpm_mode")
        if (s("spotify_client_id").isNotBlank() && LocalStore.spotifyClientId.isBlank()) {
            LocalStore.spotifyClientId = s("spotify_client_id")
        }
        if (s("wake_templates").isNotBlank() && LocalStore.wakeTemplates.isBlank()) {
            LocalStore.wakeTemplates = s("wake_templates")
        }
        if (o.has("onboarded") && o.optBoolean("onboarded") && LocalStore.geminiKey.isNotBlank()) {
            LocalStore.onboarded = true
        }
        if (o.has("wake_enabled")) LocalStore.wakeEnabled = o.optBoolean("wake_enabled", true)
        if (o.has("eco_mode")) LocalStore.ecoMode = o.optBoolean("eco_mode")
        if (o.has("trust_deletes")) LocalStore.trustDeletes = o.optBoolean("trust_deletes", true)
    }

    fun saveLocal(ctx: Context) {
        val blob = runCatching { ConfigCrypto.wrap(snapshot().toString()) }
            .getOrElse { snapshot().toString() }
        runCatching { localFile(ctx).writeText(blob) }
        runCatching { saveDownloads(ctx, blob) }
    }

    fun persistSoon(ctx: Context) {
        saveLocal(ctx)
        dev.nixi.NixiApp.scope.launch { runCatching { pushCloud() } }
    }

    private fun parseJson(raw: String): JSONObject? {
        val plain = runCatching { ConfigCrypto.unwrap(raw) }.getOrDefault(raw)
        return runCatching { JSONObject(plain) }.getOrNull()
    }

    private fun saveDownloads(ctx: Context, body: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val existing = cr.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                arrayOf(FILE),
                null,
            )
            existing?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    cr.delete(
                        android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                        ),
                        null, null,
                    )
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            cr.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, FILE).writeText(body)
        }
    }

    fun restoreLocal(ctx: Context): Boolean {
        val f = localFile(ctx)
        if (f.isFile) {
            val o = parseJson(f.readText())
            if (o != null) {
                apply(o)
                return true
            }
        }
        val fromDl = readDownloads(ctx) ?: return false
        apply(fromDl)
        saveLocal(ctx)
        return true
    }

    private fun readDownloads(ctx: Context): JSONObject? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cr = ctx.contentResolver
                val cur = cr.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    arrayOf(FILE),
                    null,
                ) ?: return null
                cur.use {
                    if (!it.moveToFirst()) return null
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)
                    )
                    val txt = cr.openInputStream(uri)?.use { s -> s.readBytes().decodeToString() }
                        ?: return null
                    parseJson(txt)
                }
            } else {
                val f = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FILE,
                )
                if (!f.isFile) null else parseJson(f.readText())
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun pushCloud() {
        if (!SupabaseHub.available) return
        val row = JSONObject()
            .put("key", SETTINGS_KEY)
            .put("value", snapshot())
        runCatching {
            SupabaseHub.c().upsert(Tables.SETTINGS, row, "key")
        }.onFailure { LogBus.log("config.push", it.message ?: "?", "warn") }
    }

    suspend fun pullCloud(): Boolean {
        if (!SupabaseHub.available) return false
        val r = runCatching {
            SupabaseHub.c().listRows(Tables.SETTINGS, mapOf("key" to "eq.$SETTINGS_KEY"), limit = 1)
        }.getOrNull() ?: return false
        if (!r.ok || r.rows.isEmpty()) return false
        val v = r.rows[0].optJSONObject("value") ?: return false
        apply(v)
        return true
    }

    /**
     * Start aplikacji: odtwórz klucze jeśli puste, zaktualizuj tabele przy
     * nowej wersji, zapisz kopię.
     */
    suspend fun bootstrap(ctx: Context) {
        val empty = LocalStore.geminiKey.isBlank() || LocalStore.supabaseUrl.isBlank()
        if (empty) {
            val local = restoreLocal(ctx)
            if (local) {
                SupabaseHub.rebuild()
                LogBus.log("config.restore", "odtworzono z pliku")
            }
        }
        if (LocalStore.supabaseUrl.isBlank() || LocalStore.supabaseKey.isBlank()) return
        SupabaseHub.rebuild()
        if (LocalStore.geminiKey.isBlank()) {
            if (pullCloud()) {
                LogBus.log("config.restore", "odtworzono z Supabase")
            }
        }
        val ver = runCatching {
            val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode
        }.getOrDefault(0)
        val needSchema = LocalStore.schemaApplied < ver
        val st = runCatching { dev.nixi.db.DbProvisioner.check() }.getOrNull()
        if (needSchema || (st != null && st.missing.isNotEmpty())) {
            val out = dev.nixi.db.DbProvisioner.provision()
            LogBus.log(
                "db.provision",
                "auto v$ver: ${out.message}",
                if (out.ok) "info" else "warn",
            )
            if (out.ok) LocalStore.schemaApplied = ver
        } else if (needSchema) {
            LocalStore.schemaApplied = ver
        }
        saveLocal(ctx)
        pushCloud()
    }
}
