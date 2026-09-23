package dev.nixi.tools

import dev.nixi.db.DiscoveredTables
import dev.nixi.db.SupabaseHub
import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Uniwersalne narzędzia Supabase: czytanie / dodawanie / edycja / usuwanie
 * WIESZCIWYCH tabel i wierszy. NIXI widzi każdą nową tabelę (discover).
 */
object SupabaseTools {

    suspend fun listTables(): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase nie jest skonfigurowany.")
        var metas = DiscoveredTables.value.value
        if (metas.isEmpty()) {
            val r = SupabaseHub.c().discover()
            if (!r.ok) return ToolResult.fail("Odkrywanie tabel nie powiodło się: ${r.error}")
            metas = DiscoveredTables.value.value
        }
        val lines = metas.map { m ->
            m.name + (if (m.columns.isNotEmpty()) " (${m.columns.joinToString(",") { it.name }})" else "")
        }
        return ToolResult.ok(
            "Tabele w Supabase (${metas.size}):\n" + lines.joinToString("\n")
        )
    }

    suspend fun describe(table: String): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase nie jest skonfigurowany.")
        val meta = DiscoveredTables.value.value.firstOrNull { it.name == table }
        val cols = meta?.columns?.joinToString(", ") { it.name + " " + it.type }
            .orEmpty()
        val count = runCatching { SupabaseHub.countRows(table) }.getOrNull()
        val acc = SupabaseHub.accessFor(table)
        return ToolResult.ok(
            "Tabela $table: kolumny: [${cols.ifBlank { "brak metadanych — wyślij db_query(limit 1) do zobaczenia wierszy" }}]. " +
                "Wierszy: ${count ?: "?"}. Uprawnienia NIXI: odczyt=${acc.read}, edycja=${acc.edit}, usuwanie=${acc.delete}."
        )
    }

    suspend fun query(table: String, filters: JSONObject, limit: Int): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase nie jest skonfigurowany.")
        val filterMap = mutableMapOf<String, String>()
        for (k in filters.keys()) {
            val v = filters.opt(k)
            if (v != null) filterMap[k] = "eq.$v"
        }
        val r = runCatching {
            SupabaseHub.listRows(table, filterMap, null, limit.coerceIn(1, 100), 0)
        }.getOrNull() ?: return ToolResult.fail("Błąd zapytania: wyjątek")
        if (!r.ok) return ToolResult.fail("Błąd: ${r.error}")
        if (r.rows.isEmpty()) return ToolResult.ok("Tabela $table: brak wierszy (po filtrach).")
        val out = JSONArray()
        r.rows.forEach { out.put(it) }
        return ToolResult.ok(
            "Tabela $table (${r.rows.size} wierszy):\n" + out.toString().take(4000)
        )
    }

    suspend fun insert(table: String, row: JSONObject): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase nie jest skonfigurowany.")
        val r = runCatching { SupabaseHub.insertRow(table, row) }.getOrNull()
            ?: return ToolResult.fail("Błąd wstawienia: wyjątek")
        if (!r.ok) return ToolResult.fail("Błąd wstawienia: ${r.error}")
        ActionNotifierNotify("NIXI: baza danych", "Dodałam wiersz do $table (${row.keys().asSequence().take(4).joinToString(",") { it }}).")
        return ToolResult.ok("Dodane do $table.")
    }

    suspend fun update(table: String, id: String, row: JSONObject): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase nie jest skonfigurowany.")
        val filters = if (id.isNotBlank()) mapOf("id" to "eq.$id") else emptyMap()
        if (filters.isEmpty()) return ToolResult.fail("Podaj id wiersza.")
        val r = runCatching { SupabaseHub.updateRow(table, filters, row) }.getOrNull()
            ?: return ToolResult.fail("Błąd edycji: wyjątek")
        if (!r.ok) return ToolResult.fail("Błąd edycji: ${r.error}")
        ActionNotifierNotify("NIXI: baza danych", "Edytowałam wiersz $id w $table.")
        return ToolResult.ok("Zaktualizowane w $table (id=$id).")
    }

    suspend fun delete(table: String, id: String): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase nie jest skonfigurowany.")
        if (id.isBlank()) return ToolResult.fail("Podaj id wiersza do usunięcia.")
        val r = runCatching { SupabaseHub.deleteRows(table, mapOf("id" to "eq.$id")) }.getOrNull()
            ?: return ToolResult.fail("Błąd usuwania: wyjątek")
        if (!r.ok) return ToolResult.fail("Błąd usuwania: ${r.error}")
        ActionNotifierNotify("NIXI: baza danych", "Usunęłam wiersz $id z $table.")
        return ToolResult.ok("Usunięto z $table.")
    }

    /**
     * DDL (nowe tabele / kolumny): klient nie może uruchamiać SQL,
     * więc model PROPONUJE SQL, a aplikacja pokazuje go użytkownikowi
     * (link do SQL Editora Supabase) i zapisuje w logach.
     */
    suspend fun ddlPropose(sql: String): ToolResult {
        val clean = sql.trim().trimEnd(';')
        if (clean.isBlank()) return ToolResult.fail("Podaj SQL (CREATE / ALTER).")
        // `matches` wymaga pełnego dopasowania CAŁEGO stringa — „CREATE TABLE x (...)"
        // nigdy go nie przechodziło, więc DDL zawsze kończył się błędem.
        val firstStatement = clean.lineSequence().firstOrNull().orEmpty().trim()
        if (!Regex("""(?i)\b(CREATE|ALTER|DROP)\b""").containsMatchIn(firstStatement)) {
            return ToolResult.fail("Podaj poprawne SQL (CREATE / ALTER / DROP).")
        }
        dev.nixi.NixiState.pendingSql.value = clean
        LogBus.log("db.ddl.proposed", clean.take(200))
        ActionNotifierNotify(
            "NIXI: propozycja SQL",
            "NIXI przygotowała zmianę struktury (nowa tabela/kolumna). Otwórz aplikację, aby ją przejrzeć."
        )
        return ToolResult.ok(
            "Przygotowałam SQL i pokazałam go użytkownikowi w aplikacji (ekran Tabele → SQL): " +
                "„${clean.take(120)}”. Poproś go o wklejenie w SQL Editorze Supabase."
        )
    }

    fun isConfiguredForAgent(): Boolean =
        SupabaseHub.available || LocalStore.supabaseUrl.isNotBlank()

    private fun ActionNotifierNotify(title: String, text: String) {
        dev.nixi.notif.ActionNotifier.notify(ToolContext.app, title, text, short = true)
    }
}
