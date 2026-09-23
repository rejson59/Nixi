package dev.nixi.tools

import dev.nixi.NixiState
import org.json.JSONObject

/** Narzędzia Spotify (Web API). */
object SpotifyTools {

    private fun notConnected(): ToolResult =
        ToolResult.fail("Spotify nie jest połączony (Ustawienia → Integracje → Spotify).")

    fun connect(clientId: String): ToolResult {
        if (clientId.isBlank()) return ToolResult.fail("Podaj Client ID Spotify (developer.spotify.com).")
        return try {
            val url = SpotifyApi.buildAuthUrl(clientId)
            val it = android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ToolContext.app.startActivity(it)
            ToolResult.ok(
                "Otworzyłam logowanie Spotify w przeglądarce. Zaloguj się i zatwierdź — dam znać powiadomieniem."
            )
        } catch (t: Throwable) {
            ToolResult.fail("Nie mogę otworzyć logowania Spotify: ${t.message}")
        }
    }

    suspend fun nowPlaying(): ToolResult {
        if (!SpotifyApi.isConnected()) return notConnected()
        return try {
            val r = SpotifyApi.request("GET", "/me/player/currently-playing")
            val j = r.getOrNull()
            if (j == null || j.length() == 0) {
                return ToolResult.ok("Nic teraz nie gra w Spotify.")
            }
            val item = j.optJSONObject("item")
                ?: return ToolResult.ok("Spotify nie ma aktywnego odtwarzacza.")
            val title = item.optString("name", "?")
            val artist = item.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "") ?: ""
            val album = item.optJSONObject("album")?.optString("name", "") ?: ""
            val playing = j.optBoolean("is_playing", false)
            val progress = (j.optDouble("progress_ms", 0.0) / 1000.0).toInt()
            val dur = (j.optDouble("duration_ms", 0.0) / 1000.0).toInt()
            ToolResult.ok(
                "Teraz w Spotify: ${if (playing) "gra" else "pauza"} — $title" +
                    (if (artist.isNotBlank()) " ($artist)" else "") +
                    (if (album.isNotBlank()) ", album: $album" else "") +
                    ". Prowadzenie: ${progress / 60}:${(progress % 60).toString().padStart(2, '0')} z " +
                    "${dur / 60}:${(dur % 60).toString().padStart(2, '0')}."
            )
        } catch (t: Throwable) {
            ToolResult.fail("Spotify: ${t.message}")
        }
    }

    suspend fun pause(): ToolResult = playback("PUT", "/me/player/pause", "Zatrzymałam Spotify.")
    suspend fun resume(): ToolResult = playback("PUT", "/me/player/play", "Wznawiam Spotify.")
    suspend fun next(): ToolResult = playback("POST", "/me/player/next", "Następny utwór.")
    suspend fun previous(): ToolResult = playback("POST", "/me/player/previous", "Poprzedni utwór.")

    suspend fun seek(positionSec: Int): ToolResult =
        playback("PUT", "/me/player/seek?position_ms=${positionSec * 1000}",
            "Przewinięto do $positionSec sekundy.")

    suspend fun volume(percent: Int): ToolResult {
        val v = (percent / 100f).coerceIn(0f, 1f)
        return playback("PUT", "/me/player/volume?volume_percent=${(v * 100).toInt()}",
            "Głośność Spotify: ${percent}%.")
    }

    /** type: track | playlist | album; query: nazwa. */
    suspend fun play(query: String, type: String): ToolResult {
        if (!SpotifyApi.isConnected()) return notConnected()
        val t = when (type.lowercase()) {
            "playlist" -> "playlist"
            "album" -> "album"
            else -> "track"
        }
        return try {
            val r = SpotifyApi.request(
                "GET", "/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&type=$t&limit=1"
            )
            val j = r.getOrNull() ?: return ToolResult.fail("Spotify: ${r.exceptionOrNull()?.message}")
            val item = j.optJSONObject("tracks")?.optJSONArray("items")?.optJSONObject(0)
                ?: j.optJSONObject("playlists")?.optJSONArray("items")?.optJSONObject(0)
                ?: j.optJSONObject("albums")?.optJSONArray("items")?.optJSONObject(0)
                ?: return ToolResult.ok("Nie znalazłam „$query” w Spotify.")
            val uri = item.getString("uri")
            val body = JSONObject().apply {
                put("context_uri", uri)
                if (t == "track") {
                    put("offset", JSONObject().put("position", 0))
                }
            }
            val p = SpotifyApi.request("PUT", "/me/player/play", body)
            if (p.isFailure) return ToolResult.fail("Spotify: ${p.exceptionOrNull()?.message}")
            ToolResult.ok("Gram w Spotify: ${item.optString("name", query)}.")
        } catch (t: Throwable) {
            ToolResult.fail("Błąd Spotify: ${t.message}")
        }
    }

    suspend fun listPlaylists(limit: Int): ToolResult {
        if (!SpotifyApi.isConnected()) return notConnected()
        return try {
            val r = SpotifyApi.request("GET", "/me/playlists?limit=${limit.coerceIn(1, 25)}")
            val j = r.getOrNull() ?: return ToolResult.fail("Spotify: ${r.exceptionOrNull()?.message}")
            val arr = j.optJSONObject("playlists")?.optJSONArray("items")
            if (arr == null || arr.length() == 0) return ToolResult.ok("Brak playlist.")
            val names = (0 until arr.length()).joinToString(", ") {
                arr.getJSONObject(it).optString("name", "?")
            }
            ToolResult.ok("Playlisty: $names")
        } catch (t: Throwable) {
            ToolResult.fail("Błąd Spotify: ${t.message}")
        }
    }

    private suspend fun playback(method: String, path: String, okMsg: String): ToolResult {
        return try {
            val r = SpotifyApi.request(method, path)
            r.getOrNull()?.let { ToolResult.ok(okMsg) }
                ?: ToolResult.fail("Spotify: ${r.exceptionOrNull()?.message}")
        } catch (t: Throwable) {
            ToolResult.fail("Błąd Spotify: ${t.message}")
        }
    }
}
