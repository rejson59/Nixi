package dev.nixi.db

import android.content.Context
import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralny dostęp do Supabase.
 *
 * Uniwersalność: lista tabel pochodzi z OpenAPI PostgREST (discover()),
 * więc każda nowa tabela w projekcie jest automatycznie widoczna w aplikacji
 * i dostępna dla narzędzi NIXI (zgodnie z matrixą dostępu nixi_access).
 */
object SupabaseHub {

    private lateinit var client: PostgrestClient
    private var ready = false

    /** Dostęp NIXI do tabel: read / edit / delete (z nixi_access, z fallbackiem domyślnym). */
    data class Access(val read: Boolean, val edit: Boolean, val delete: Boolean)

    private val accessCache = mutableMapOf<String, Access>()
    val accessCacheFlow = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Access>>(emptyMap())

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    fun init(context: Context) {
        rebuild()
    }

    @Volatile private var lastRefreshAt = 0L

    /**
     * Odświeża: odkrywanie tabel + uprawnienia + admin_table. (safe z UI)
     * Throttling: onResume bywa wołane bardzo często (np. powrót z każdego
     * ekranu), a każde odświeżenie to 3 zapytania do Supabase.
     */
    fun refreshAll(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAt < 15_000) return
        lastRefreshAt = now
        dev.nixi.NixiApp.scope.launch {
            if (!available) return@launch
            runCatching { c().discover() }
                .onSuccess { dev.nixi.util.LogBus.log("db.discover", "odkryto ${dev.nixi.db.DiscoveredTables.value.value.size} tabel") }
                .onFailure { dev.nixi.util.LogBus.log("db.discover", it.message ?: "?", "warn") }
            runCatching { refreshAccess() }
            runCatching { loadAdmin() }
        }
    }

    /** Ponowne zbudowanie klienta po zmianie URL/klaucza w ustawieniach. */
    fun rebuild() {
        accessCache.clear()
        accessCacheFlow.value = emptyMap()
        if (isConfigured()) {
            client = PostgrestClient(LocalStore.supabaseUrl, LocalStore.supabaseKey)
            ready = true
        } else {
            ready = false
        }
    }

    fun isConfigured(): Boolean =
        LocalStore.supabaseUrl.startsWith("http") && LocalStore.supabaseKey.isNotBlank()

    val available: Boolean get() = ready && isConfigured()

    fun c(): PostgrestClient {
        check(ready) { "Supabase nie jest skonfigurowany" }
        return client
    }

    // ── Dostęp NIXI (nixi_access) ─────────────────────────────────────────

    private fun defaultAccess(table: String): Access {
        val isCore = Tables.CORE.contains(table)
        return Access(read = true, edit = isCore, delete = false)
    }

    suspend fun refreshAccess() {
        if (!available) return
        val result = runCatching {
            c().listRows(Tables.ACCESS, limit = 200)
        }.getOrElse {
            LogBus.log("access.refresh", it.message ?: "?", "warn"); return
        }
        if (!result.ok) {
            // tabela nixi_access nie istnieje -> używamy wartości domyślnych
            LogBus.log("access.refresh", "tabela nixi_access brak, domyślne uprawnienia", "warn")
            return
        }
        val map = mutableMapOf<String, Access>()
        for (row in result.rows) {
            val table = row.optString("table_name")
            if (table.isBlank()) continue
            map[table] = Access(
                read = row.optBoolean("can_read", true),
                edit = row.optBoolean("can_edit", true),
                delete = row.optBoolean("can_delete", false),
            )
        }
        accessCache.putAll(map)
        accessCacheFlow.value = accessCache.toMap()
    }

    fun accessFor(table: String): Access =
        accessCache[table] ?: defaultAccess(table)

    suspend fun setAccess(table: String, read: Boolean, edit: Boolean, delete: Boolean) {
        if (!available) return
        val row = JSONObject().apply {
            put("table_name", table)
            put("can_read", read)
            put("can_edit", edit)
            put("can_delete", delete)
        }
        val upd = c().update(Tables.ACCESS, mapOf("table_name" to "eq.$table"), row)
        if (upd.ok) {
            val a = Access(read, edit, delete)
            accessCache[table] = a
            accessCacheFlow.value = accessCache.toMap()
            LogBus.log("access.set", "$table r=$read e=$edit d=$delete")
        }
    }

    // ── CRUD ogólny (używany przez UI i narzędzia) ────────────────────────

    suspend fun listRows(table: String, filters: Map<String, String> = emptyMap(),
                         orderBy: String? = null, limit: Int = 50, offset: Int = 0): PostgrestClient.Result {
        checkCan(table, "read")
        return c().listRows(table, filters, orderBy, limit, offset)
    }

    suspend fun countRows(table: String): Int {
        checkCan(table, "read")
        return c().count(table) ?: 0
    }

    suspend fun insertRow(table: String, row: JSONObject): PostgrestClient.Result {
        checkCan(table, "edit")
        val r = c().insert(table, row)
        if (r.ok) LogBus.log("db.insert", "$table: ${row.keys().asSequence().take(4).joinToString(", ") { it }}")
        return r
    }

    suspend fun updateRow(table: String, filters: Map<String, String>, row: JSONObject): PostgrestClient.Result {
        checkCan(table, "edit")
        val r = c().update(table, filters, row)
        if (r.ok) LogBus.log("db.update", "$table ${filters.entries.firstOrNull()?.let { "${it.key}=${it.value}" } ?: ""}")
        return r
    }

    suspend fun deleteRows(table: String, filters: Map<String, String>): PostgrestClient.Result {
        checkCan(table, "delete")
        val r = c().delete(table, filters)
        if (r.ok) LogBus.log("db.delete", "$table ${filters.values.joinToString(",")}")
        return r
    }

    private fun checkCan(table: String, level: String) {
        val a = accessFor(table)
        require(level == "read" || a.read) { "NIXI nie ma dostępu odczytu do $table" }
        require(level == "edit" || a.edit) { "NIXI nie ma uprawnień edycji do $table" }
        require(level == "delete" || a.delete) { "NIXI nie ma uprawnień usuwania do $table" }
    }

    // ── admin_table (klucz/wartość) ────────────────────────────────────────

    private val adminCache = mutableMapOf<String, String>()
    val adminCacheFlow = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())

    suspend fun loadAdmin() {
        if (!available) { adminCacheFlow.value = emptyMap(); return }
        val r = c().listRows(Tables.ADMIN, limit = 100)
        val map = mutableMapOf<String, String>()
        if (r.ok) for (row in r.rows) {
            val k = row.optString("key"); val v = row.optString("value")
            if (k.isNotBlank()) map[k] = v
        }
        adminCache.clear(); adminCache.putAll(map)
        adminCacheFlow.value = map
    }

    fun admin(key: String, default: String = ""): String =
        adminCache[key] ?: default

    suspend fun saveAdmin(key: String, value: String) {
        if (!available) return
        val row = JSONObject().put("key", key).put("value", value)
            .put("updated_at", iso.format(Date()))
        val r = c().update(Tables.ADMIN, mapOf("key" to "eq.$key"), row)
        if (!r.ok) c().insert(Tables.ADMIN, row)
        adminCache[key] = value
        adminCacheFlow.value = adminCache.toMap()
        LogBus.log("admin.save", key)
    }

    // ── Pamięć długotrwała ────────────────────────────────────────────────

    suspend fun upsertFact(key: String, value: String, category: String = "ogólne") {
        if (!available) return
        val row = JSONObject().apply {
            put("key", key)
            put("value", value)
            put("category", category)
            put("updated_at", iso.format(Date()))
        }
        val r = c().update(Tables.MEMORY, mapOf("key" to "eq.$key"), row)
        if (!r.ok) c().insert(Tables.MEMORY, row)
    }

    suspend fun loadFacts(limit: Int = 60): List<JSONObject> =
        if (available) c().listRows(Tables.MEMORY, limit = limit).rows else emptyList()

    suspend fun deleteFact(key: String): Boolean {
        if (!available) return false
        return c().delete(Tables.MEMORY, mapOf("key" to "eq.$key")).ok
    }

    /** Najnowsze "ciekawe rozmowy" — do podglądu w aplikacji. */
    suspend fun recentConversations(limit: Int = 15): List<JSONObject> =
        if (available) c().listRows(Tables.RECENT, orderBy = "created_at.desc", limit = limit).rows
        else emptyList()

    // ── Kalendarz ─────────────────────────────────────────────────────────

    suspend fun calendarEventsBetween(startIso: String, endIso: String): List<JSONObject> =
        if (available) c().listRows(
            Tables.CALENDAR,
            mapOf("start" to "gte.$startIso", "end" to "lte.$endIso"),
            orderBy = "start.asc", limit = 200
        ).rows else emptyList()

    // ── Powiadomienia o akcjach (tabela system_logs / errors) ─────────────

    suspend fun insertLog(entry: LogBus.LogEntry) {
        if (!available) return
        val row = JSONObject().apply {
            put("ts", entry.ts)
            put("action", entry.action)
            put("detail", entry.detail)
            put("status", entry.status)
        }
        c().insert(entry.table, row)
    }

    suspend fun reportError(tag: String, t: Throwable) {
        if (!available) return
        val row = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("tag", tag)
            put("message", (t.message ?: t.javaClass.simpleName).take(900))
        }
        c().insert(Tables.ERRORS, row)
    }

    suspend fun recentLogs(limit: Int = 100, table: String = Tables.LOGS): List<JSONObject> =
        if (available) c().listRows(table, orderBy = "ts.desc", limit = limit).rows else emptyList()

    // ── Reguły ciche (silent_rules) ───────────────────────────────────────

    suspend fun loadRules(): List<JSONObject> =
        if (available) c().listRows(Tables.RULES, limit = 100).rows else emptyList()

    suspend fun saveRule(rule: JSONObject) {
        if (!available) return
        if (rule.has("id")) {
            c().update(Tables.RULES, mapOf("id" to "eq.${rule.getInt("id")}"), rule)
        } else {
            c().insert(Tables.RULES, rule)
        }
        LogBus.log("rule.save", rule.optString("name"))
    }

    // ── Plan lekcji ───────────────────────────────────────────────────────

    suspend fun todayLessons(dayOfWeek: Int): List<JSONObject> =
        if (available) c().listRows(Tables.LESSONS, mapOf("day_of_week" to "eq.$dayOfWeek"),
            orderBy = "start.asc", limit = 50).rows else emptyList()
}
