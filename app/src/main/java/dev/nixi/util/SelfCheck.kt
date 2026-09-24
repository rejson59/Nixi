package dev.nixi.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.nixi.NixiState
import dev.nixi.accessibility.NixiAccessibilityService
import dev.nixi.db.DbProvisioner
import dev.nixi.db.SupabaseHub
import dev.nixi.notif.NixiNotificationListener
import dev.nixi.store.LocalStore
import dev.nixi.wake.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * SAMOKONTROLA NIXI — jedno miejsce, które mówi wprost, co działa, a co nie.
 *
 * Powstała po zgłoszeniu „NIXI nie odpowiada, a w ustawieniach pisze, że
 * usługa dostępności nie działa”: zamiast zgadywać, aplikacja sprawdza po
 * kolei klucz API, nazwę modelu, uprawnienia i bazę, i pokazuje wynik razem
 * z podpowiedzią, co zrobić.
 *
 * Wszystko dzieje się lokalnie; jedyne zapytanie na zewnątrz to lista modeli
 * Gemini (żeby sprawdzić klucz i nazwę modelu).
 */
object SelfCheck {

    enum class Level { OK, WARN, ERR }

    data class Item(
        val label: String,
        val level: Level,
        val detail: String,
        val fix: String = "",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun run(context: Context): List<Item> {
        NixiState.selfCheckRunning.value = true
        val items = ArrayList<Item>()
        try {
            val key = LocalStore.geminiKey
            if (key.isBlank()) {
                items.add(
                    Item(
                        "Klucz API Gemini", Level.ERR, "brak klucza",
                        "Wklej klucz z aistudio.google.com/apikey w Ustawieniach → Mózg."
                    )
                )
            } else {
                items.addAll(checkGemini(key))
            }
            items.add(checkMic(context))
            items.add(checkWake())
            items.add(checkSupabase())
            items.add(checkAccessibility(context))
            items.add(checkNotifications(context))
        } catch (t: Throwable) {
            items.add(Item("Samokontrola", Level.ERR, t.message ?: "nieznany błąd"))
        } finally {
            NixiState.selfCheck.value = items
            NixiState.selfCheckRunning.value = false
        }
        return items
    }

    /** Klucz + model: jedno zapytanie do listy modeli odpowiada na oba pytania. */
    private suspend fun checkGemini(key: String): List<Item> = withContext(Dispatchers.IO) {
        val out = ArrayList<Item>()
        val model = LocalStore.geminiModel
        try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=" + key.trim())
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    out.add(
                        Item(
                            "Klucz API Gemini", Level.ERR, "serwer odpowiedział ${resp.code}",
                            problemHint(resp.code, text)
                        )
                    )
                    return@withContext out
                }
                out.add(Item("Klucz API Gemini", Level.OK, "klucz działa"))

                val names = ArrayList<String>()
                val live = ArrayList<String>()
                runCatching {
                    val arr = JSONObject(text).optJSONArray("models")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val m = arr.getJSONObject(i)
                            val name = m.optString("name", "").removePrefix("models/")
                            if (name.isNotBlank()) names.add(name)
                            val methods = m.optJSONArray("supportedGenerationMethods")
                            var liveOk = false
                            if (methods != null) {
                                for (j in 0 until methods.length()) {
                                    if (methods.optString(j) == "bidiGenerateContent") liveOk = true
                                }
                            }
                            if (liveOk && name.isNotBlank()) live.add(name)
                        }
                    }
                }
                if (names.isEmpty()) {
                    out.add(Item("Model Live", Level.WARN, "nie mogę odczytać listy modeli"))
                } else if (live.contains(model)) {
                    out.add(Item("Model Live", Level.OK, "$model obsługuje rozmowę głosową"))
                } else if (names.contains(model)) {
                    out.add(
                        Item(
                            "Model Live", Level.ERR, "$model nie obsługuje rozmowy głosowej",
                            "Ten model nie ma trybu Live. Wybierz: " + live.take(3).joinToString(", ")
                        )
                    )
                } else {
                    out.add(
                        Item(
                            "Model Live", Level.ERR, "nie widzę modelu „$model”",
                            "Dostępne modele Live: " + (if (live.isEmpty()) "brak" else live.take(3).joinToString(", "))
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            out.add(
                Item(
                    "Klucz API Gemini", Level.ERR, "brak połączenia: ${t.message}",
                    "Sprawdź internet i spróbuj ponownie."
                )
            )
        }
        out
    }

    private fun problemHint(code: Int, body: String): String = when (code) {
        400 -> "Klucz wygląda na niepoprawny. Skopiuj go ponownie z aistudio.google.com/apikey."
        401, 403 -> "Klucz nie ma dostępu do Gemini API — sprawdź, czy API jest włączone w projekcie."
        429 -> "Limit zapytań — poczekaj minutę i spróbuj ponownie."
        else -> body.take(160)
    }

    private fun checkMic(context: Context): Item {
        val ok = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return if (ok) Item("Mikrofon", Level.OK, "uprawnienie przyznane")
        else Item(
            "Mikrofon", Level.ERR, "brak uprawnienia",
            "Ustawienia systemowe → Aplikacje → NIXI → Uprawnienia → Mikrofon."
        )
    }

    private fun checkWake(): Item {
        if (!LocalStore.wakeEnabled) {
            return Item("Nasłuch „Hej Nixi”", Level.WARN, "wyłączony", "Włącz w Ustawieniach → Nasłuch.")
        }
        if (LocalStore.wakeNeedsEnroll) {
            return Item(
                "Nasłuch „Hej Nixi”", Level.ERR, "brak wzorca frazy",
                "Nagraj „Hej Nixi” w Ustawieniach → Nasłuch (3 próby)."
            )
        }
        return if (WakeWordService.running) {
            Item("Nasłuch „Hej Nixi”", Level.OK, "działa (${LocalStore.wakeCpuMsPerMin} ms CPU/min)")
        } else {
            Item(
                "Nasłuch „Hej Nixi”", Level.WARN, "usługa nie działa",
                "Otwórz aplikację (usługa startuje razem z nią) albo włącz nasłuch ponownie."
            )
        }
    }

    private fun checkSupabase(): Item {
        val url = LocalStore.supabaseUrl
        if (url.isBlank()) {
            return Item(
                "Supabase", Level.WARN, "nie podłączony",
                "Wklej URL i klucz anon w Ustawieniach → Dane (działa bez tego, ale bez pamięci i tabel)."
            )
        }
        if (!SupabaseHub.available) {
            return Item(
                "Supabase", Level.ERR, "brak połączenia z projektem",
                "Sprawdź URL i klucz anon — i czy tabele zostały utworzone (Ustawienia → Dane → Struktura bazy)."
            )
        }
        val status = DbProvisioner.status.value
        return when {
            status == null -> Item("Supabase", Level.OK, "połączony")
            status.missing.isEmpty() -> Item("Supabase", Level.OK, "połączony, ${status.present.size} tabel gotowych")
            else -> Item(
                "Supabase", Level.WARN,
                "brakuje tabel: ${status.missing.take(4).joinToString(", ")}" +
                    (if (status.missing.size > 4) "…" else ""),
                "Ustawienia → Dane → „Utwórz / zaktualizuj tabele”."
            )
        }
    }

    private fun checkAccessibility(context: Context): Item {
        val ready = NixiAccessibilityService.isAvailable()
        val enabled = NixiAccessibilityService.isEnabledInSystem(context)
        return when {
            ready -> Item("Usługa dostępności", Level.OK, "włączona i gotowa")
            enabled -> Item(
                "Usługa dostępności", Level.WARN, "włączona w systemie, czeka na połączenie",
                "Zamknij i otwórz aplikację — usługa połączy się sama. Jeśli nie, wyłącz ją i włącz ponownie."
            )
            else -> Item(
                "Usługa dostępności", Level.WARN, "wyłączona (potrzebna tylko do trybu ręcznego)",
                "Ustawienia systemowe → Dostępność → NIXI."
            )
        }
    }

    private fun checkNotifications(context: Context): Item =
        if (NixiNotificationListener.isEnabled(context)) {
            Item("Dostęp do powiadomień", Level.OK, "włączony")
        } else {
            Item(
                "Dostęp do powiadomień", Level.WARN, "wyłączony",
                "Bez tego nie ma cichych reguł, raportów i pauzy muzyki."
            )
        }
}
