package dev.nixi.live

import android.content.Context
import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.notif.ActionNotifier
import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * PAMIĘĆ DŁUGOTRWAŁA po rozmowie:
 *  - lekki wywołanie REST generateContent (tani model flash, ten sam klucz)
 *    zwraca JSON: trwałe fakty + krótkie podsumowanie rozmowy,
 *  - fakty -> memory_facts (upsert), podsumowanie -> recent_conversations,
 *  - krótkie powiadomienie, gdy NIXI coś zapamiętała (przezroczystość).
 * NIGDY nie pokazuje transkrypcji użytkownikowi — tylko po cichu uczy się.
 */
object PostSessionMemory {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    fun run(context: Context, transcripts: List<Pair<String, String>>, durationSec: Long) {
        NixiAppScopeMem.launch {
            runCatching { process(context, transcripts, durationSec) }
                .onFailure { LogBus.log("memory.post", it.message ?: "?", "warn") }
        }
    }

    private object NixiAppScopeMem {
        fun launch(block: suspend () -> Unit) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).let { s ->
                s.launch(block)
            }
        }
    }

    private suspend fun process(context: Context, transcripts: List<Pair<String, String>>, durationSec: Long) {
        if (!SupabaseHub.available) return
        val key = LocalStore.geminiKey
        if (key.isBlank()) return
        val convo = transcripts.takeLast(40)
            .joinToString("\n") { (role, text) ->
                (if (role == "user") "Użytkownik" else "NIXI") + ": " + text
            }
        if (convo.length < 80) return

        val model = LocalStore.memoryModel
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put(
                        "text",
                        "Jesteś modułem pamięci asystentki NIXI. Zwracaj WYŁĄCZNIE poprawny JSON " +
                            "bez komentarzy: {\"facts\":[{\"key\":\"krótki_klucz\",\"value\":\"fakt o użytkowniku\",\"category\":\"...\"}],\"summary\":\"1-2 zdania o rozmowie\",\"topics\":\"słowa, kluczowe\"}. " +
                            "Fakty tylko trwałe i użyteczne (preferencje, plany, ludzie, obowiązki). Jeśli brak — puste tablice."
                    )
                ))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "parts", JSONArray().put(
                            JSONObject().put(
                                "text",
                                "Rozmowa (czas ${durationSec}s):\n$convo"
                            )
                        )
                    )
                )
            )
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val text: String = withTimeoutOrNull(30_000) {
            withContext(Dispatchers.IO) {
                val resp = http.newCall(
                    Request.Builder().url(url)
                        .post(body.toString().toRequestBody(json)).build()
                ).execute()
                val t = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${t.take(120)}")
                val j = JSONObject(t)
                j.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            }
        } ?: return

        val parsed = parseJsonLoose(text) ?: return
        var factsSaved = 0
        val facts = parsed.optJSONArray("facts")
        if (facts != null) {
            for (i in 0 until facts.length()) {
                val f = facts.getJSONObject(i)
                val key = f.optString("key").trim()
                val value = f.optString("value").trim()
                if (key.isBlank() || value.isBlank()) continue
                SupabaseHub.upsertFact(key, value, f.optString("category", "ogólne"))
                factsSaved++
            }
        }
        val summary = parsed.optString("summary").trim()
        if (summary.isNotBlank()) {
            val row = JSONObject().apply {
                put("summary", summary)
                put("topics", parsed.optString("topics", ""))
                put("duration_sec", durationSec)
                put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            }
            SupabaseHub.c().insert(Tables.RECENT, row)
        }
        if (factsSaved > 0) {
            ActionNotifier.notify(
                context, "NIXI: pamięć",
                "Po rozmowie zapamiętałam $factsSaved nowych faktów o Tobie.",
                short = true
            )
            LogBus.log("memory.post", "faktów: $factsSaved, podsumowanie: ${summary.take(80)}")
        } else {
            LogBus.log("memory.post", "bez nowych faktów")
        }
    }

    /** JSON bywa owinięty w ```json ... ``` — tolerujemy. */
    private fun parseJsonLoose(text: String): JSONObject? {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            t = t.substringBeforeLast("```").trim()
        }
        return try {
            JSONObject(t)
        } catch (_: Exception) {
            null
        }
    }
}
