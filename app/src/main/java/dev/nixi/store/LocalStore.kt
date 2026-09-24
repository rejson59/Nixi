package dev.nixi.store

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Lokalna konfiguracja aplikacji (klucze API, głos, wake-word, integracje).
 * Trzymana prywatnie na urządzeniu — Supabase jest wyłącznie dla danych użytkownika.
 */
object LocalStore {

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("nixi", Context.MODE_PRIVATE)
    }

    // ── Gemini ─────────────────────────────────────────────────────────────
    var geminiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        set(v) = prefs.edit().putString("gemini_key", v.trim()).apply()

    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-3.8-live") ?: "gemini-3.8-live"
        set(v) = prefs.edit().putString("gemini_model", v.trim()).apply()

    var memoryModel: String
        get() = prefs.getString("memory_model", "gemini-3.8-flash") ?: "gemini-3.8-flash"
        set(v) = prefs.edit().putString("memory_model", v.trim()).apply()

    /**
     * Który wariant wiadomości `setup` rozmawia z Gemini (patrz
     * [dev.nixi.live.LiveSessionService.buildSetup]): 0 = wszystko w
     * `generationConfig` (referencja API), 1 = na najwyższym poziomie
     * (przewodnik WebSocket), 2 = minimalny. Aplikacja dopasowuje go sama
     * i zapisuje ten, który zadziałał, żeby nie powtarzać nieudanych prób.
     */
    var liveSetupVariant: Int
        get() = prefs.getInt("live_setup_variant", 0)
        set(v) = prefs.edit().putInt("live_setup_variant", v).apply()

    // ── Supabase ───────────────────────────────────────────────────────────
    var supabaseUrl: String
        get() = prefs.getString("supabase_url", "") ?: ""
        set(v) = prefs.edit().putString("supabase_url", v.trim().trimEnd('/')).apply()

    var supabaseKey: String
        get() = prefs.getString("supabase_key", "") ?: ""
        set(v) = prefs.edit().putString("supabase_key", v.trim()).apply()

    /**
     * Opcjonalny „personal access token” Supabase (sbp_…) — tylko do
     * automatycznego tworzenia i aktualizowania tabel. Klucz anon nie ma
     * prawa zmieniać struktury bazy (to zabezpieczenie Supabase), więc bez
     * tego tokenu aplikacja przygotowuje SQL i prowadzi Cię do SQL Editora.
     */
    var supabasePat: String
        get() = prefs.getString("supabase_pat", "") ?: ""
        set(v) = prefs.edit().putString("supabase_pat", v.trim()).apply()

    // ── Głos / sesja ───────────────────────────────────────────────────────
    var voiceName: String
        get() = prefs.getString("voice_name", "Puck") ?: "Puck"
        set(v) = prefs.edit().putString("voice_name", v.trim()).apply()

    var playRate: Float
        get() = prefs.getFloat("play_rate", 1.0f)
        set(v) = prefs.edit().putFloat("play_rate", v).apply()

    var outputVolume: Float
        get() = prefs.getFloat("output_volume", 1.0f)
        set(v) = prefs.edit().putFloat("output_volume", v).apply()

    var tpmMode: String
        get() = prefs.getString("tpm_mode", "standard") ?: "standard" // eco | standard | custom | off
        set(v) = prefs.edit().putString("tpm_mode", v).apply()

    var tpmCustomLimit: Int
        get() = prefs.getInt("tpm_custom_limit", 55000)
        set(v) = prefs.edit().putInt("tpm_custom_limit", v).apply()

    // ── Wake-word ──────────────────────────────────────────────────────────
    var wakeEnabled: Boolean
        get() = prefs.getBoolean("wake_enabled", true)
        set(v) = prefs.edit().putBoolean("wake_enabled", v).apply()

    var wakeSensitivity: Float
        get() = prefs.getFloat("wake_sensitivity", 0.5f) // 0..1
        set(v) = prefs.edit().putFloat("wake_sensitivity", v).apply()

    var wakeTemplates: String
        get() = prefs.getString("wake_templates", "") ?: ""
        set(v) = prefs.edit().putString("wake_templates", v).apply()

    /**
     * True, gdy zapisany szablon pochodzi ze starszego detektora (inne cechy)
     * — wtedy nasłuch działa, ale nic nie rozpozna, dopóki użytkownik nie
     * nagra frazy ponownie. Pokazujemy to na ekranie głównym.
     */
    var wakeNeedsEnroll: Boolean
        get() = prefs.getBoolean("wake_needs_enroll", false)
        set(v) = prefs.edit().putBoolean("wake_needs_enroll", v).apply()

    var dingEnabled: Boolean
        get() = prefs.getBoolean("ding_enabled", true)
        set(v) = prefs.edit().putBoolean("ding_enabled", v).apply()

    var ecoMode: Boolean
        get() = prefs.getBoolean("eco_mode", false)
        set(v) = prefs.edit().putBoolean("eco_mode", v).apply()

    /** Statystyki CPU nasłuchu (ms na minutę) — ekran "Oszczędność". */
    var wakeCpuMsPerMin: Long
        get() = prefs.getLong("wake_cpu_ms_min", 0L)
        set(v) = prefs.edit().putLong("wake_cpu_ms_min", v).apply()

    // ── Spotify ────────────────────────────────────────────────────────────
    var spotifyClientId: String
        get() = prefs.getString("spotify_client_id", "") ?: ""
        set(v) = prefs.edit().putString("spotify_client_id", v.trim()).apply()

    var spotifyAccessToken: String
        get() = prefs.getString("spotify_access", "") ?: ""
        set(v) = prefs.edit().putString("spotify_access", v).apply()

    var spotifyRefreshToken: String
        get() = prefs.getString("spotify_refresh", "") ?: ""
        set(v) = prefs.edit().putString("spotify_refresh", v).apply()

    var spotifyTokenExpiry: Long
        get() = prefs.getLong("spotify_token_expiry", 0L)
        set(v) = prefs.edit().putLong("spotify_token_expiry", v).apply()

    var spotifyDeviceCode: String
        get() = prefs.getString("spotify_device_code", "") ?: ""
        set(v) = prefs.edit().putString("spotify_device_code", v).apply()

    var spotifyPollInterval: Int
        get() = prefs.getInt("spotify_poll_interval", 5)
        set(v) = prefs.edit().putInt("spotify_poll_interval", v).apply()

    var spotifyCodeVerifier: String
        get() = prefs.getString("spotify_code_verifier", "") ?: ""
        set(v) = prefs.edit().putString("spotify_code_verifier", v).apply()

    // ── Inne ───────────────────────────────────────────────────────────────
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    /**
     * True = NIXI usuwa (kalendarz, budzik, wiersz) bez okna potwierdzenia.
     * Domyślnie false: pierwsze „Tak” w rozmowie wystarcza na resztę sesji.
     */
    var trustDeletes: Boolean
        get() = prefs.getBoolean("trust_deletes", true)
        set(v) = prefs.edit().putBoolean("trust_deletes", v).apply()

    /** True = nie spamuj powiadomieniami o każdej akcji podczas rozmowy. */
    var quietDuringSession: Boolean
        get() = prefs.getBoolean("quiet_during_session", true)
        set(v) = prefs.edit().putBoolean("quiet_during_session", v).apply()

    /** Cache persona + profilu użytkownika (żeby sesja startowała od razu). */
    var promptStatic: String
        get() = prefs.getString("prompt_static", "") ?: ""
        set(v) = prefs.edit().putString("prompt_static", v).apply()

    var promptStaticAt: Long
        get() = prefs.getLong("prompt_static_at", 0L)
        set(v) = prefs.edit().putLong("prompt_static_at", v).apply()

    var knownTables: String
        get() = prefs.getString("known_tables", "") ?: ""
        set(v) = prefs.edit().putString("known_tables", v).apply()

    fun knownTableList(): List<String> = try {
        val arr = JSONArray(knownTables)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun setKnownTables(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        knownTables = arr.toString()
    }

    var lastSessionDuration: Long
        get() = prefs.getLong("last_session_dur", 0L)
        set(v) = prefs.edit().putLong("last_session_dur", v).apply()

    // ── Diagnostyka ostatniej sesji („NIXI nie odpowiada”) ─────────────────
    // Zapisujemy suche fakty, żeby dało się je pokazać w aplikacji (karta
    // „Rozmowa z Gemini”) i w samokontroli — bez zgadywania, co się stało.

    /** Dlaczego zakończyła się ostatnia rozmowa (np. „bezczynność (5 min)”). */
    var lastSessionReason: String
        get() = prefs.getString("last_session_reason", "") ?: ""
        set(v) = prefs.edit().putString("last_session_reason", v).apply()

    /** Ile klatek audio NIXI wysłała do modelu w ostatniej sesji. */
    var lastSessionAudioChunks: Int
        get() = prefs.getInt("last_session_chunks", 0)
        set(v) = prefs.edit().putInt("last_session_chunks", v).apply()

    /** Ile razy model odpowiedział dźwiękiem w ostatniej sesji. */
    var lastSessionAudioReplies: Int
        get() = prefs.getInt("last_session_replies", 0)
        set(v) = prefs.edit().putInt("last_session_replies", v).apply()

    /** Który wariant konfiguracji sesji zadziałał (0/1/2, -1 = żaden). */
    var lastSessionVariant: Int
        get() = prefs.getInt("last_session_variant", -1)
        set(v) = prefs.edit().putInt("last_session_variant", v).apply()

    /** Czy w ostatniej sesji serwer potwierdził gotowość (setupComplete). */
    var lastSessionSetupOk: Boolean
        get() = prefs.getBoolean("last_session_setup_ok", false)
        set(v) = prefs.edit().putBoolean("last_session_setup_ok", v).apply()

    fun clearAllData() {
        prefs.edit().clear().apply()
    }
}
