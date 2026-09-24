package dev.nixi

import dev.nixi.db.DbSchema
import dev.nixi.db.Tables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test struktury bazy „na sucho”, bez Supabase.
 *
 * Pilnuje trzech rzeczy, które łatwo zepsuć przy edycji [DbSchema] i które
 * kończą się cichym błędem dopiero na telefonie:
 *  1. każde polecenie z [DbSchema.RPC_SQL] przechodzi filtr funkcji
 *     `nixi_exec_sql` (inaczej auto-zakładanie tabel zwróci błąd),
 *  2. żadne polecenie nie zawiera `$$` ani `;` wewnątrz literału
 *     (dzielimy SQL po średnikach, więc to by go rozbiło),
 *  3. wszystkie tabele z [Tables.CORE] mają swoją definicję.
 */
class DbSchemaTest {

    /** To samo dzielenie, które robi funkcja w bazie. */
    private fun statements(sql: String): List<String> =
        sql.split(';')
            .map { it.trim() }
            .map { st -> st.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").trim() }
            .filter { it.isNotEmpty() }

    private val allowed = Regex("^(create|alter|drop|insert)\\s")
    private val forbidden = Regex("(role|grant|revoke|password|extension|database|pg_)")

    @Test
    fun `kazde polecenie RPC przechodzi filtr funkcji`() {
        val cmds = statements(DbSchema.RPC_SQL)
        assertTrue("RPC_SQL jest pusty", cmds.size > 50)
        for (c in cmds) {
            val low = c.lowercase()
            assertTrue("odrzucony typ: ${c.take(70)}", allowed.containsMatchIn(low))
            assertFalse("zakazane słowo: ${c.take(70)}", forbidden.containsMatchIn(low))
            assertTrue(
                "polecenie nie dotyczy tabeli NIXI: ${c.take(70)}",
                Tables.CORE.any { low.contains(it) }
            )
        }
    }

    @Test
    fun `brak dollar-quotingu w poleceniach wykonywanych przez aplikacje`() {
        // `$$` (blok funkcji) zawiera średniki i rozbiłoby dzielenie po ';'
        assertFalse(DbSchema.RPC_SQL.contains("$" + "$"))
    }

    @Test
    fun `brak srednikow w literalach`() {
        val literals = Regex("'([^']*)'").findAll(DbSchema.RPC_SQL).map { it.groupValues[1] }
        for (l in literals) {
            assertFalse("średnik w literale: $l", l.contains(';'))
        }
    }

    @Test
    fun `kazda tabela CORE ma definicje`() {
        val sql = DbSchema.SQL.lowercase()
        for (t in Tables.CORE) {
            assertTrue("brak definicji tabeli $t", sql.contains("create table if not exists public.$t ("))
            assertTrue("brak polityki dla $t", sql.contains("create policy nixi_all on public.$t"))
            assertTrue(
                "brak aktualizacji kolumn dla $t",
                sql.contains("alter table public.$t add column if not exists")
            )
        }
    }

    @Test
    fun `pelny SQL tworzy furtke dla aplikacji`() {
        val sql = DbSchema.SQL
        assertTrue(sql.contains("create or replace function public.nixi_exec_sql"))
        assertTrue(sql.contains("grant execute on function public.nixi_exec_sql(text) to anon, authenticated"))
        // seed.sql jest generowany z tego samego źródła — musi zgłaszać tę samą wersję
        assertEquals(Tables.CORE.size, DbSchema.CORE.size)
    }

    @Test
    fun `kolumny dodaja sie bez not null`() {
        // `add column ... not null` wywala się na tabelach z danymi
        val addColumn = Regex("alter table public\\.[a-z_]+ add column if not exists [^;]+")
        for (m in addColumn.findAll(DbSchema.SQL)) {
            assertFalse("not null przy dodawaniu kolumny: ${m.value}", m.value.contains("not null"))
        }
    }
}
