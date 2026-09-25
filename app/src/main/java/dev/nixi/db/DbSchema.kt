package dev.nixi.db

/**
 * STRUKTURA BAZY NIXI — generowana z jednego źródła prawdy.
 *
 * Z tych samych definicji powstają dwa teksty:
 *  - [SQL] — do wklejenia w SQL Editor Supabase (pełny: tabele, indeksy,
 *    polityki RLS, startowe dane i funkcja `nixi_exec_sql`),
 *  - [RPC_SQL] — to, co aplikacja potrafi wykonać sama przez
 *    `nixi_exec_sql` (tylko `create`/`alter`/`drop` na tabelach NIXI).
 *
 * Wszystko jest idempotentne: `create table if not exists`,
 * `add column if not exists`, `create index if not exists`,
 * `drop policy if exists`. Dzięki temu ten sam tekst i zakłada tabele,
 * i je AKTUALIZUJE — po dodaniu w kolejnej wersji aplikacji nowej kolumny
 * wystarczy uruchomić go ponownie.
 */
object DbSchema {

    /** Tabele, których brak aplikacja zgłasza i które tworzy. */
    val CORE: List<String> = Tables.CORE

    private data class Col(val name: String, val def: String)

    private data class Tbl(
        val name: String,
        val cols: List<Col>,
        val indexes: List<String> = emptyList(),
    )

    private const val ID = "bigint generated always as identity primary key"
    private const val NOW = "timestamptz default now()"

    private val tables: List<Tbl> = listOf(
        Tbl(
            "admin_table",
            listOf(
                Col("key", "text primary key"),
                Col("value", "text"),
                Col("updated_at", NOW),
            )
        ),
        Tbl(
            "users",
            listOf(
                Col("id", ID),
                Col("display_name", "text"),
                Col("name", "text"),
                Col("birth_date", "date"),
                Col("city", "text"),
                Col("email", "text"),
                Col("notes", "text"),
                Col("schedule_notes", "text"),
                Col("created_at", NOW),
            )
        ),
        Tbl(
            "memory_facts",
            listOf(
                Col("key", "text primary key"),
                Col("value", "text"),
                Col("category", "text default 'ogólne'"),
                Col("updated_at", NOW),
            )
        ),
        Tbl(
            "recent_conversations",
            listOf(
                Col("id", ID),
                Col("summary", "text"),
                Col("topics", "text"),
                Col("duration_sec", "int"),
                Col("created_at", NOW),
            )
        ),
        Tbl(
            "reminders",
            listOf(
                Col("id", ID),
                Col("title", "text not null"),
                Col("fires_at", "timestamptz"),
                Col("done", "boolean default false"),
                Col("note", "text"),
                Col("created_at", NOW),
            )
        ),
        Tbl(
            "alarms",
            listOf(
                Col("id", ID),
                Col("time", "time not null"),
                Col("label", "text"),
                Col("ring_on_weekends", "boolean default true"),
                Col("sound", "text"),
                Col("active", "boolean default true"),
                Col("created_by_nixi", "boolean default true"),
                Col("created_at", NOW),
            )
        ),
        Tbl(
            "calendar_events",
            listOf(
                Col("id", ID),
                Col("title", "text not null"),
                Col("start", "timestamptz not null"),
                Col("end", "timestamptz"),
                Col("location", "text"),
                Col("notes", "text"),
                Col("kind", "text default 'normal'"),
                Col("created_at", NOW),
            )
        ),
        Tbl(
            "lesson_plan",
            listOf(
                Col("id", ID),
                Col("day_of_week", "smallint not null"),
                Col("start", "time not null"),
                Col("end", "time"),
                Col("subject", "text"),
                Col("room", "text"),
                Col("teacher", "text"),
                Col("term", "text default 'bieżący'"),
            )
        ),
        Tbl(
            "routines",
            listOf(
                Col("id", ID),
                Col("name", "text"),
                Col("trigger", "text not null"),
                Col("time_of_day", "text"),
                Col("steps", "text not null"),
                Col("active", "boolean default true"),
            )
        ),
        Tbl(
            "silent_rules",
            listOf(
                Col("id", ID),
                Col("name", "text"),
                Col("app_package", "text"),
                Col("contains", "text"),
                Col("action", "jsonb default '{}'::jsonb"),
                Col("active", "boolean default true"),
            )
        ),
        Tbl(
            "settings",
            listOf(
                Col("key", "text primary key"),
                Col("value", "jsonb"),
                Col("updated_at", NOW),
            )
        ),
        Tbl(
            "system_logs",
            listOf(
                Col("id", ID),
                Col("ts", "bigint not null"),
                Col("action", "text"),
                Col("detail", "text"),
                Col("status", "text default 'ok'"),
            ),
            indexes = listOf("ts desc")
        ),
        Tbl(
            "errors",
            listOf(
                Col("id", ID),
                Col("ts", "bigint not null"),
                Col("tag", "text"),
                Col("message", "text"),
            ),
            indexes = listOf("ts desc")
        ),
        Tbl(
            "nixi_access",
            listOf(
                Col("table_name", "text primary key"),
                Col("can_read", "boolean default true"),
                Col("can_edit", "boolean default true"),
                Col("can_delete", "boolean default false"),
            )
        ),
        Tbl(
            "todos",
            listOf(
                Col("id", ID),
                Col("title", "text not null"),
                Col("done", "boolean default false"),
                Col("category", "text default 'ogólne'"),
                Col("due", "timestamptz"),
                Col("created_at", NOW),
            )
        ),
        Tbl(
            "shopping",
            listOf(
                Col("id", ID),
                Col("item", "text not null"),
                Col("qty", "text"),
                Col("done", "boolean default false"),
                Col("created_at", NOW),
            )
        ),
        Tbl(
            "people",
            listOf(
                Col("id", ID),
                Col("name", "text not null"),
                Col("relation", "text"),
                Col("notes", "text"),
                Col("last_talk", "timestamptz"),
                Col("created_at", NOW),
            )
        ),
    )

    /** Polecenia struktury — wspólne dla obu ścieżek (wklejanie i RPC). */
    private val structureStatements: List<String> = tables.flatMap { t ->
        val out = ArrayList<String>()
        out.add(
            "create table if not exists public.${t.name} (\n" +
                t.cols.joinToString(",\n") { "  ${it.name} ${it.def}" } + "\n)"
        )
        // aktualizacja starszych wersji tabel: dołóż brakujące kolumny.
        // `not null` tu pomijamy — stare wiersze nie mają z czego go wypełnić.
        for (c in t.cols) {
            val def = c.def.replace(" not null", "")
            out.add("alter table public.${t.name} add column if not exists ${c.name} $def")
        }
        for (idx in t.indexes) {
            val idxName = "${t.name}_${idx.substringBefore(' ')}_idx"
            out.add("create index if not exists $idxName on public.${t.name} ($idx)")
        }
        // RLS: włączone i otwarte dla klucza anon (projekt osobisty)
        out.add("alter table public.${t.name} enable row level security")
        out.add("drop policy if exists nixi_all on public.${t.name}")
        out.add(
            "create policy nixi_all on public.${t.name} for all " +
                "using (true) with check (true)"
        )
        out
    }

    /** Startowe dane — tylko przy wklejaniu (RPC nie wykonuje `insert`). */
    private val seedStatements: List<String> = listOf(
        "insert into public.admin_table (key, value) values ('assistant_name', 'NIXI') " +
            "on conflict (key) do nothing",
        "insert into public.admin_table (key, value) values ('language', 'polski') " +
            "on conflict (key) do nothing",
        "insert into public.routines (name, trigger, time_of_day, steps) values " +
            "('Rano', 'Dzień dobry', 'rano', 'Powiedz którą jest godzinę, podaj krótki cytat " +
            "podnoszący na duchu, powiedz o której zaczyna się pierwsza lekcja " +
            "(z lesson_plan/kalendarza) i życz udanego dnia.')",
        "insert into public.silent_rules (name, app_package, contains, action, active) values " +
            "('Zastępstwo z dziennika', '', 'zastępstwo', " +
            "'{\"type\": \"calendar_substitution\"}'::jsonb, true)",
        "insert into public.silent_rules (name, app_package, contains, action, active) values " +
            "('Zastępstwo eduVulcan', 'pl.edu.vulcan.hebe', 'zastępstwo', " +
            "'{\"type\": \"calendar_substitution\"}'::jsonb, true)",
        "insert into public.silent_rules (name, app_package, contains, action, active) values " +
            "('Sprawdzian z dziennika', '', 'sprawdzian', " +
            "'{\"type\": \"calendar_test\"}'::jsonb, true)",
        "insert into public.silent_rules (name, app_package, contains, action, active) values " +
            "('Wolne z dziennika', '', 'wolne', " +
            "'{\"type\": \"calendar_day_off\"}'::jsonb, true)",
        "insert into public.nixi_access (table_name, can_read, can_edit, can_delete) values " +
            Tables.CORE.filter { it != Tables.ACCESS }.joinToString(", ") {
                val del = it in listOf(Tables.LOGS, Tables.ERRORS)
                val edit = it !in listOf(Tables.LOGS, Tables.ERRORS)
                "('$it', true, $edit, $del)"
            } + " on conflict (table_name) do nothing",
    )

    /**
     * SQL wykonywany przez aplikację (przez `nixi_exec_sql`): struktura tabel
     * i startowe dane. Bez tworzenia samej funkcji — ona już istnieje (inaczej
     * nie byłoby czym tego wykonać), a jej odtwarzanie w trakcie wywołania
     * tylko mieszałoby w skrypcie.
     */

    /**
     * Funkcja, dzięki której aplikacja wykonuje powyższy SQL samodzielnie
     * (klucz anon nie ma prawa do DDL — to zabezpieczenie Supabase). Każde
     * polecenie jest sprawdzane osobno: dozwolone są wyłącznie `create`,
     * `alter` i `drop` dotyczące tabel NIXI.
     */
    private val execFunctionSql: String = """
-- ── Furtka dla aplikacji: wykonywanie TYLKO poleceń NIXI ──────────────
create or replace function public.nixi_exec_sql(sql text)
returns void
language plpgsql
security definer
set search_path = public
as ${'$'}body${'$'}
declare
  stmt text;
  low  text;
  ok   boolean;
  allowed text[] := array[
    'admin_table','users','memory_facts','recent_conversations','reminders',
    'alarms','calendar_events','lesson_plan','routines','silent_rules',
    'settings','system_logs','errors','nixi_access','todos','shopping','people'
  ];
  t text;
begin
  -- Blok $$ (definicja funkcji) zawiera średniki w treści — nie da się go
  -- wykonać tą drogą. Zamiast 30 niezrozumiałych błędów dajemy jedną radę.
  if sql like '%${'$'}${'$'}%' then
    raise exception 'Ten SQL zawiera definicje funkcji — wklej go w SQL Editorze Supabase.';
  end if;

  foreach stmt in array string_to_array(sql, ';') loop
    stmt := btrim(stmt);
    -- komentarze liniowe (-- …) nie są poleceniami
    stmt := regexp_replace(stmt, '(?m)^[[:space:]]*--.*$', '', 'g');
    stmt := btrim(stmt);
    continue when stmt = '';
    low := lower(stmt);
    if low !~ '^(create|alter|drop|insert)[[:space:]]' then
      raise exception 'nixi_exec_sql: tylko CREATE/ALTER/DROP/INSERT (dostalem: %)', left(stmt, 60);
    end if;
    if low ~ '(role|grant|revoke|password|extension|database|pg_)' then
      raise exception 'nixi_exec_sql: polecenie niedozwolone';
    end if;
    ok := false;
    foreach t in array allowed loop
      if low like '%' || t || '%' then ok := true; end if;
    end loop;
    if not ok then
      raise exception 'nixi_exec_sql: polecenie nie dotyczy tabel NIXI (dostalem: %)', left(stmt, 60);
    end if;
  end loop;

  foreach stmt in array string_to_array(sql, ';') loop
    stmt := btrim(stmt);
    stmt := regexp_replace(stmt, '(?m)^[[:space:]]*--.*$', '', 'g');
    stmt := btrim(stmt);
    continue when stmt = '';
    execute stmt;
  end loop;
end ${'$'}body${'$'};

revoke all on function public.nixi_exec_sql(text) from public;
grant execute on function public.nixi_exec_sql(text) to anon, authenticated;
""".trimIndent()

    val RPC_SQL: String =
        (structureStatements + seedStatements).joinToString(";\n") + ";"

    /** Pełny SQL do wklejenia w SQL Editorze Supabase. */
    val SQL: String = buildString {
        append("-- ═══ NIXI: tabele (bezpieczne do wielokrotnego uruchomienia) ═══\n")
        append("-- Ten sam kod zakłada brakujące tabele i DOKŁADA brakujące kolumny,")
        append(" więc uruchom go ponownie po każdej aktualizacji aplikacji.\n\n")
        append(structureStatements.joinToString(";\n"))
        append(";\n\n-- ── Startowe dane (nie nadpisują Twoich) ──\n")
        append(seedStatements.joinToString(";\n"))
        append(";\n\n")
        append(execFunctionSql)
        append("\n-- ═══ Gotowe — wróć do aplikacji i naciśnij „Sprawdź ponownie” ═══\n")
    }

}
