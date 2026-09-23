package dev.nixi.db

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cienki klient REST Supabase (PostgREST) — zero dodatkowych zależności.
 * auth: anon/service key w nagłówkach. RLS decyduje na poziomie bazy.
 */
class PostgrestClient(private val projectUrl: String, private val key: String) {

    data class TableMeta(
        val name: String,
        val columns: List<ColumnMeta>,
    )
    data class ColumnMeta(val name: String, val type: String)

    data class Result(
        val ok: Boolean,
        val status: Int,
        val rows: List<JSONObject>,
        val json: JSONObject?,
        val error: String?,
        val count: Int?,
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun base(): String = projectUrl.trimEnd('/') + "/rest/v1"

    private fun headers(prefer: String? = null): Map<String, String> = buildMap {
        put("apikey", key)
        put("Authorization", "Bearer $key")
        put("Accept", "application/json")
        if (prefer != null) put("Prefer", prefer)
    }

    /**
     * Wykonanie żądania. [path] względem /rest/v1 (np. "/calendar_events").
     * [query] — parametry PostgREST (select, limit, filtr col=eq.value ...).
     */
    suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        prefer: String? = null,
    ): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val url = java.net.HttpUrl.parse(base() + path)!!
                .newBuilder()
                .apply { for ((k, v) in query) addQueryParameter(k, v) }
                .build()
            val bodyBytes: okhttp3.RequestBody? = when {
                body != null -> body.toString().toRequestBody(jsonType)
                method == "DELETE" || method == "PATCH" ->
                    JSONObject("{}").toString().toRequestBody(jsonType)
                else -> null
            }
            val resp = http.newCall(
                Request.Builder()
                    .url(url)
                    .headers(headers(prefer))
                    .method(method, bodyBytes)
                    .build()
            ).execute()
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            val rows = if (text.trimStart().startsWith('[')) {
                val arr = runCatching { JSONArray(text) }.getOrNull()
                (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
            } else {
                emptyList()
            }
            val contentRange = resp.header("Content-Range")
            val count = contentRange?.substringAfterLast('/')?.removeSuffix(")")?.toIntOrNull()
            Result(
                ok = resp.isSuccessful,
                status = resp.code,
                rows = rows,
                json = json,
                error = if (resp.isSuccessful) null else (json?.optString("message")
                    ?: json?.optString("error") ?: text.take(300)),
                count = count,
            )
        } catch (t: Throwable) {
            Result(false, -1, emptyList(), null, t.message ?: "network error", null)
        }
    }

    // ── CRUD ───────────────────────────────────────────────────────────────

    suspend fun listRows(
        table: String,
        filters: Map<String, String> = emptyMap(),
        orderBy: String? = null,
        limit: Int = 50,
        offset: Int = 0,
        withCount: Boolean = false,
    ): Result {
        val query = LinkedHashMap<String, String>()
        query["select"] = "*"
        query["limit"] = limit.toString()
        query["offset"] = offset.toString()
        if (withCount) query["count"] = "exact"
        if (orderBy != null) query["order"] = orderBy
        query.putAll(filters)
        return request("GET", "/" + table, query)
    }

    suspend fun insert(table: String, row: JSONObject): Result =
        request("POST", "/" + table, body = row, prefer = "return=representation,resolution=ignore-duplicates")

    suspend fun insertAll(table: String, rows: List<JSONObject>): Result {
        val body = JSONArray()
        rows.forEach { body.put(it) }
        return rawBody("POST", "/" + table, body, "return=representation,resolution=ignore-duplicates")
    }

    suspend fun update(table: String, filters: Map<String, String>, row: JSONObject): Result =
        request("PATCH", "/" + table, filters, row, "return=representation")

    suspend fun delete(table: String, filters: Map<String, String>): Result =
        request("DELETE", "/" + table, filters, prefer = "return=representation")

    /** Liczba wierszy (count=exact, select=1 — tanio). */
    suspend fun count(table: String): Int? =
        listRows(table, limit = 1, withCount = true).count

    // ── Odkrywanie tabel (OpenAPI PostgREST) ──────────────────────────────

    /**
     * Uniwersalne wykrywanie tabel: GET /rest/v1/ zwraca OpenAPI z pełną listą
     * ścieżek (tabel) oraz kolumn. Dzięki temu NIXI widzi każdą nową tabelę,
     * którą utworzysz w Supabase — bez restartu i bez zmian w kodzie.
     */
    suspend fun discover(): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val req = Request.Builder().url(base() + "/").get()
                .headers(headers()).build()
            val resp = http.newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return@withContext Result(false, resp.code, emptyList(),
                    null, "openapi: " + text.take(200), null)
            }
            val openapi = JSONObject(text)
            val names = linkedMapOf<String, MutableList<ColumnMeta>>()
            val paths = openapi.optJSONObject("paths")
            if (paths != null) {
                for (name in paths.keys()) {
                    val clean = name.trimStart('/')
                    if (clean.isNotBlank()) names[clean] = mutableListOf()
                }
            }
            // kolumny z components.schemas.<schema>.tables
            val components = openapi.optJSONObject("components")
            val schemas = components?.optJSONObject("schemas")
            if (schemas != null) {
                for (schemaName in schemas.keys()) {
                    val tablesObj = schemas.getJSONObject(schemaName).optJSONObject("tables")
                    if (tablesObj == null) continue
                    for (tableName in tablesObj.keys()) {
                        val cols = tablesObj.getJSONObject(tableName).optJSONObject("columns")
                        val list = names.getOrPut(tableName) { mutableListOf() }
                        if (cols != null) {
                            for (col in cols.keys()) {
                                val colJson = cols.getJSONObject(col)
                                list.add(
                                    ColumnMeta(
                                        name = col,
                                        type = colJson.optString("type") +
                                            if (colJson.optString("format").isNotBlank())
                                                ":" + colJson.getString("format") else ""
                                    )
                                )
                            }
                        }
                    }
                }
            }
            val result = Result(true, 200, emptyList(), openapi, null, null)
            DiscoveredTables.value = names.map { (n, cols) -> TableMeta(n, cols) }
            return@withContext result
        } catch (t: Throwable) {
            Result(false, -1, emptyList(), null, t.message ?: "discover failed", null)
        }
    }

    private suspend fun rawBody(
        method: String,
        path: String,
        body: Any,
        prefer: String? = null,
    ): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(base() + path)
                .headers(headers(prefer))
                .method(method, body.toString().toRequestBody(jsonType))
                .build()
            val resp = http.newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            Result(
                ok = resp.isSuccessful,
                status = resp.code,
                rows = try {
                    val arr = JSONArray(text)
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                } catch (_: Exception) { emptyList() },
                json = runCatching { JSONObject(text) }.getOrNull(),
                error = if (resp.isSuccessful) null else text.take(300),
                count = resp.header("Content-Range")?.substringAfterLast('/')?.removeSuffix(")")?.toIntOrNull(),
            )
        } catch (t: Throwable) {
            Result(false, -1, emptyList(), null, t.message ?: "error", null)
        }
    }
}

/** Wynik ostatniego odkrywania tabel (widoczny dla UI i narzędzi). */
object DiscoveredTables {
    val value = kotlinx.coroutines.flow.MutableStateFlow<List<PostgrestClient.TableMeta>>(emptyList())
}
