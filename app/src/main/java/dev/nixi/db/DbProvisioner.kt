package dev.nixi.db

import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ZAKŁADANIE I AKTUALIZACJA TABEL — „niech same się utworzą”.
 *
 * Klucz anon Supabase nie ma prawa zmieniać struktury bazy (i dobrze —
 * to zabezpieczenie, nie usterka). Dlatego aplikacja ma trzy drogi, od
 * najwygodniejszej:
 *
 *  1. **Token osobisty (sbp_…)** — jeśli go podasz, aplikacja zakłada
 *     i aktualizuje tabele sama, jednym zapytaniem do API zarządzania
 *     Supabase. Nic nie musisz wklejać.
 *  2. **Funkcja `nixi_exec_sql`** — jeśli wcześniej uruchomiłeś SQL-a NIXI
 *     (choćby raz, ręcznie), ta funkcja zostaje w bazie i od tego momentu
 *     aplikacja robi wszystko sama, bez tokenu.
 *  3. **Wklejenie jednego SQL-a** — gdy nie ma ani tokenu, ani funkcji:
 *     aplikacja przygotowuje pełny SQL (kopiuje go do schowka i otwiera
 *     SQL Editor jednym tapnięciem), a potem sprawdza, co powstało.
 *
 * Każda droga kończy się tym samym: [status] mówi, które tabele są gotowe,
 * a których brakuje.
 */
object DbProvisioner {

    /** Stan struktury bazy: co jest, czego brakuje, czym próbowaliśmy. */
    data class Status(
        val present: List<String>,
        val missing: List<String>,
        val checkedAt: Long = System.currentTimeMillis(),
    ) {
        val ready: Boolean get() = missing.isEmpty()
        val summary: String
            get() = when {
                present.isEmpty() && missing.isEmpty() -> "nie sprawdzano"
                missing.isEmpty() -> "wszystkie ${present.size} tabel gotowych"
                present.isEmpty() -> "brakuje wszystkich tabel (${missing.size})"
                else -> "gotowe: ${present.size}, brakuje: ${missing.size}"
            }
    }

    /** Wynik próby zakładania tabel. */
    data class Outcome(
        val ok: Boolean,
        val message: String,
        /** True, gdy trzeba wkleić SQL ręcznie (aplikacja nie mogła sama). */
        val needsManualSql: Boolean = false,
    )

    private val statusFlow = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = statusFlow

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    private fun restBase(): String = LocalStore.supabaseUrl.trimEnd('/') + "/rest/v1"

    private fun restHeaders(): Map<String, String> = mapOf(
        "apikey" to LocalStore.supabaseKey,
        "Authorization" to "Bearer ${LocalStore.supabaseKey}",
        "Content-Type" to "application/json",
    )

    /**
     * Sprawdza, które z tabel NIXI istnieją. Pyta PostgREST o jeden wiersz
     * (`limit=1`) — brak tabeli to 404, więc nie trzeba znać schematu.
     */
    suspend fun check(): Status = withContext(Dispatchers.IO) {
        if (LocalStore.supabaseUrl.isBlank() || LocalStore.supabaseKey.isBlank()) {
            val s = Status(emptyList(), CORE_TABLES)
            statusFlow.value = s
            return@withContext s
        }
        val present = ArrayList<String>()
        val missing = ArrayList<String>()
        for (t in CORE_TABLES) {
            val ok = try {
                val req = Request.Builder()
                    .url("${restBase()}/$t?select=*&limit=1")
                    .apply { restHeaders().forEach { (k, v) -> addHeader(k, v) } }
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    // 200 = tabela jest; 404/400 = nie ma (albo brak w niej kolumn)
                    resp.isSuccessful
                }
            } catch (t2: Throwable) {
                LogBus.log("db.check", "$t: ${t2.message}", "warn")
                false
            }
            if (ok) present.add(t) else missing.add(t)
        }
        val s = Status(present, missing)
        statusFlow.value = s
        s
    }

    /**
     * Zakłada i aktualizuje tabele. Najpierw próbuje bez pytania Cię o
     * cokolwiek (token osobisty → funkcja w bazie), a dopiero na końcu
     * oddaje SQL do wklejenia.
     */
    suspend fun provision(): Outcome {
        if (LocalStore.supabaseUrl.isBlank() || LocalStore.supabaseKey.isBlank()) {
            return Outcome(false, "Najpierw podaj URL i klucz anon projektu.")
        }
        ingestKeys()
        // 1) token osobisty — pełna automatyzacja
        val pat = LocalStore.supabasePat
        if (pat.isNotBlank()) {
            val r = runViaManagementApi(pat)
            if (r.ok) {
                check()
                return r
            }
            LogBus.log("db.provision", "management api: ${r.message}", "warn")
        }
        // 1b) klucz service_role (często wklejany w pole „anon”) — próbujemy
        //     wewnętrzne endpointy SQL Supabase, bez SQL Editora.
        if (jwtRole(LocalStore.supabaseKey) == "service_role") {
            val pg = runViaPgMeta()
            if (pg.ok) {
                check()
                return pg
            }
            LogBus.log("db.provision", "pg-meta: ${pg.message}", "warn")
        }
        // 2) funkcja w bazie (jednorazowe wklejenie SQL-a wystarcza na zawsze)
        val rpc = runViaRpc()
        if (rpc.ok) {
            check()
            return rpc
        }
        // 3) ręczne wklejenie
        val after = check()
        if (after.ready) {
            return Outcome(true, "Struktura bazy jest już gotowa.")
        }
        return Outcome(
            false,
            "Nie mogę sam zmienić struktury bazy: klucz anon nie ma do tego prawa. " +
                "Skopiowałem gotowy SQL do schowka — wklej go w SQL Editorze Supabase " +
                "(przycisk obok otwiera go od razu) i naciśnij „Sprawdź ponownie”. " +
                "Jeśli wolisz, żebym robiła to sama, dodaj token osobisty Supabase (sbp_…) " +
                "albo wklej klucz service_role zamiast anon.",
            needsManualSql = true,
        )
    }

    /**
     * Gdy w pole klucza wpadnie token sbp_… albo JWT service_role,
     * rozkładamy to od razu — użytkownik nie musi zgadywać, które pole.
     */
    private fun ingestKeys() {
        val k = LocalStore.supabaseKey.trim()
        if (k.startsWith("sbp_") && LocalStore.supabasePat.isBlank()) {
            LocalStore.supabasePat = k
        }
        val p = LocalStore.supabasePat.trim()
        if (p.startsWith("eyJ") && jwtRole(p) == "anon" && LocalStore.supabaseKey.isBlank()) {
            LocalStore.supabaseKey = p
        }
    }

    private fun jwtRole(token: String): String {
        if (!token.startsWith("eyJ")) return ""
        return try {
            val payload = token.split('.').getOrNull(1) ?: return ""
            val pad = payload + "=".repeat((4 - payload.length % 4) % 4)
            val json = String(android.util.Base64.decode(pad, android.util.Base64.URL_SAFE))
            JSONObject(json).optString("role", "")
        } catch (_: Throwable) {
            ""
        }
    }

    /** Droga 1: API zarządzania Supabase (`/v1/projects/{ref}/database/query`). */
    private suspend fun runViaManagementApi(pat: String): Outcome = withContext(Dispatchers.IO) {
        val ref = projectRef()
        if (ref.isBlank()) return@withContext Outcome(false, "Nie rozpoznaję adresu projektu w URL.")
        try {
            val body = JSONObject().put("query", DbSchema.SQL).toString()
            val req = Request.Builder()
                .url("https://api.supabase.com/v1/projects/$ref/database/query")
                .addHeader("Authorization", "Bearer $pat")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(json))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    LogBus.log("db.provision", "tabele założone przez API zarządzania")
                    Outcome(true, "Tabele gotowe (utworzone i zaktualizowane automatycznie).")
                } else {
                    val msg = when (resp.code) {
                        401 -> "Token osobisty nie pasuje do tego projektu."
                        403 -> "Token nie ma uprawnień do zarządzania tym projektem."
                        else -> "Błąd ${resp.code}: ${text.take(180)}"
                    }
                    Outcome(false, msg)
                }
            }
        } catch (t: Throwable) {
            Outcome(false, "Brak połączenia: ${t.message}")
        }
    }

    /**
     * Droga 1b: endpoint SQL studia / pg-meta (działa, gdy wkleisz klucz
     * service_role — ten klucz omija RLS i bywa przepuszczany przez Kong).
     */
    private suspend fun runViaPgMeta(): Outcome = withContext(Dispatchers.IO) {
        val base = LocalStore.supabaseUrl.trimEnd('/')
        val key = LocalStore.supabaseKey
        val urls = listOf(
            "$base/pg/query",
            "$base/pg-meta/default/query",
            "$base/pg-meta/query",
        )
        val body = JSONObject().put("query", DbSchema.SQL).toString()
        var last = "brak odpowiedzi"
        for (u in urls) {
            try {
                val req = Request.Builder()
                    .url(u)
                    .addHeader("apikey", key)
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody(json))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        LogBus.log("db.provision", "tabele założone przez $u")
                        return@withContext Outcome(true, "Tabele gotowe (utworzone i zaktualizowane automatycznie).")
                    }
                    last = "${resp.code} ${text.take(120)}"
                }
            } catch (t: Throwable) {
                last = t.message ?: "błąd"
            }
        }
        Outcome(false, last)
    }

    /** Droga 2: funkcja `nixi_exec_sql` w bazie (przez PostgREST RPC). */
    private suspend fun runViaRpc(): Outcome = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("sql", DbSchema.RPC_SQL).toString()
            val req = Request.Builder()
                .url("${restBase()}/rpc/nixi_exec_sql")
                .apply { restHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .post(body.toRequestBody(json))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> {
                        LogBus.log("db.provision", "tabele założone przez nixi_exec_sql")
                        Outcome(true, "Tabele gotowe (utworzone i zaktualizowane automatycznie).")
                    }
                    resp.code == 404 -> Outcome(false, "Funkcja nixi_exec_sql nie istnieje w bazie.")
                    else -> Outcome(false, "RPC ${resp.code}: ${text.take(180)}")
                }
            }
        } catch (t: Throwable) {
            Outcome(false, "Brak połączenia: ${t.message}")
        }
    }

    /** Sprawdza tylko kilka tabel — używane przy starcie, żeby nie męczyć sieci. */
    suspend fun quickOk(): Boolean = withContext(Dispatchers.IO) {
        val t = Tables.RECENT
        try {
            val req = Request.Builder()
                .url("${restBase()}/$t?select=id&limit=1")
                .apply { restHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .get()
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Throwable) {
            false
        }
    }

    /** Adres projektu z URL-a (`https://xyz.supabase.co` → `xyz`). */
    fun projectRef(): String {
        val url = LocalStore.supabaseUrl
        if (url.isBlank()) return ""
        return runCatching {
            android.net.Uri.parse(url).host.orEmpty().substringBefore('.')
        }.getOrDefault("")
    }

    private val CORE_TABLES: List<String> = Tables.CORE
}
