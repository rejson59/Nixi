package dev.nixi.tools

import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Spotify Web API — OAuth Device Flow (użytkownik wkleja tylko Client ID
 * z developer.spotify.com i zatwierdza kod w przeglądarce — bez sekretów
 * w aplikacji).
 */
object SpotifyApi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val BASE = "https://api.spotify.com/v1"

    var deviceFlowUri: String = ""
        private set
    var deviceFlowUserCode: String = ""
        private set

    fun isConnected(): Boolean =
        LocalStore.spotifyClientId.isNotBlank() &&
            (LocalStore.spotifyAccessToken.isNotBlank() || LocalStore.spotifyRefreshToken.isNotBlank())

    /** Start Device Flow. Zwraca (ok, komunikat z kodem). */
    suspend fun startDeviceFlow(clientId: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("scope", "user-read-currently-playing user-read-playback-state " +
                    "user-modify-playback-state user-read-playback-state user-library-read")
                .build()
            val resp = http.newCall(Request.Builder().url(TOKEN_URL).post(form).build()).execute()
            val body = resp.body?.string().orEmpty()
            val j = JSONObject(body)
            if (!resp.isSuccessful) {
                return@withContext false to "Błąd: ${j.optString("error_description", body.take(120))}"
            }
            LocalStore.spotifyClientId = clientId
            LocalStore.spotifyDeviceCode = j.getString("device_code")
            LocalStore.spotifyPollInterval = j.optInt("interval", 5)
            deviceFlowUri = j.getString("verification_uri_complete")
            deviceFlowUserCode = j.getString("user_code")
            true to "Otwórz: ${j.getString("verification_uri")}\nKod: ${j.getString("user_code")}"
        } catch (t: Throwable) {
            false to (t.message ?: "błąd sieci")
        }
    }

    /** Polling Device Flow do momentu autoryzacji (max ~5 min). */
    suspend fun pollDeviceFlow(maxSeconds: Int = 300): String = withContext(Dispatchers.IO) {
        val code = LocalStore.spotifyDeviceCode
        val clientId = LocalStore.spotifyClientId
        if (code.isBlank() || clientId.isBlank()) return@withContext "Brak aktywnego kodu."
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxSeconds * 1000L) {
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("device_code", code)
                .build()
            val resp = http.newCall(Request.Builder().url(TOKEN_URL).post(form).build()).execute()
            val j = JSONObject(resp.body?.string().orEmpty())
            val status = j.optString("error", j.optString("status", ""))
            if (resp.isSuccessful && j.has("access_token")) {
                LocalStore.spotifyAccessToken = j.getString("access_token")
                LocalStore.spotifyRefreshToken = j.optString("refresh_token", "")
                LocalStore.spotifyTokenExpiry =
                    System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
                LocalStore.spotifyDeviceCode = ""
                LogBus.log("spotify.auth", "połączono")
                return@withContext "Spotify połączony!"
            }
            when (status) {
                "authorization_pending" -> Unit
                "slow_down" -> Thread.sleep(
                    (LocalStore.spotifyPollInterval + 5) * 1000L.toLong()
                )
                "access_denied" -> return@withContext "Odmowa autoryzacji (zamknięto kod?)."
                "expired_token" -> return@withContext "Kod wygasł — spróbuj ponownie."
                else -> {
                    if (resp.code == 400) {
                        // status nieznany
                    }
                }
            }
            Thread.sleep(LocalStore.spotifyPollInterval * 1000L.toLong())
        }
        "Zbyt długo czekano na kod — spróbuj ponownie."
    }

    fun disconnect() {
        LocalStore.spotifyAccessToken = ""
        LocalStore.spotifyRefreshToken = ""
        LocalStore.spotifyTokenExpiry = 0
    }

    private suspend fun refreshIfNeeded() {
        if (LocalStore.spotifyTokenExpiry - 5 * 60_000 > System.currentTimeMillis()) return
        val refresh = LocalStore.spotifyRefreshToken
        val clientId = LocalStore.spotifyClientId
        if (refresh.isBlank() || clientId.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val form = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .build()
                val resp = http.newCall(Request.Builder().url(TOKEN_URL).post(form).build()).execute()
                val j = JSONObject(resp.body?.string().orEmpty())
                if (resp.isSuccessful) {
                    LocalStore.spotifyAccessToken = j.getString("access_token")
                    j.optString("refresh_token").takeIf { it.isNotBlank() }
                        ?.let { LocalStore.spotifyRefreshToken = it }
                    LocalStore.spotifyTokenExpiry =
                        System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
                }
            } catch (t: Throwable) {
                LogBus.log("spotify.refresh", t.message ?: "?", "warn")
            }
        }
    }

    suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        query: String = ""
    ): Result<JSONObject> {
        refreshIfNeeded()
        val token = LocalStore.spotifyAccessToken
        if (token.isBlank()) return Result.failure(Exception("Spotify niepołączony"))
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(BASE + path + query)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .method(method, body?.toString()?.toRequestBody(json)
                        ?: (if (method != "GET") "".toRequestBody(json) else null))
                    .build()
                val resp = http.newCall(req).execute()
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("${resp.code}: ${text.take(160)}")
                    )
                }
                Result.success(if (text.isBlank()) JSONObject() else JSONObject(text))
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }
}
