#!/usr/bin/env python3
"""
Generuje `supabase/seed.sql` z jednego źródła prawdy:
`app/src/main/java/dev/nixi/db/DbSchema.kt`.

Dzięki temu SQL, który wklejasz w Supabase, jest DOKŁADNIE tym samym SQL-em,
który aplikacja wykonuje sama (przez funkcję `nixi_exec_sql`).

Użycie:
    python3 tools/gen_seed_sql.py          # zapisuje supabase/seed.sql
    python3 tools/gen_seed_sql.py --check  # tylko sprawdza, czy się zgadza
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "app/src/main/java/dev/nixi/db/DbSchema.kt"
TABLES = ROOT / "app/src/main/java/dev/nixi/db/Tables.kt"
OUT = ROOT / "supabase/seed.sql"

# tabele „tylko do odczytu” dla NIXI + te, w których wolno kasować
NO_EDIT = {"system_logs", "errors"}
NO_DELETE = {"admin_table", "users", "settings"}


def kt_string(literal: str) -> str:
    """Zamienia literał Kotlina na tekst (obsługa ${'$'} i \\uXXXX)."""
    literal = literal.replace("${'$'}", "$")
    literal = literal.replace('\\"', '"').replace("\\n", "\n").replace("\\t", "\t")
    return re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), literal)


def parse_constants(src: str) -> dict:
    """Stałe tekstowe z DbSchema.kt (ID, NOW, …)."""
    out = {}
    for m in re.finditer(r'private const val ([A-Z_]+) = "([^"]*)"', src):
        out[m.group(1)] = kt_string(m.group(2))
    return out


def parse_col_defs(block: str, consts: dict):
    """Kolumny jednej tabeli: Col("nazwa", "typ") albo Col("nazwa", STAŁA)."""
    out = []
    for m in re.finditer(r'Col\(\s*"([a-z_]+)"\s*,\s*(?:"([^"]*)"|([A-Z_]+))\s*\)', block):
        name, literal, const = m.group(1), m.group(2), m.group(3)
        if literal is not None:
            out.append((name, kt_string(literal)))
        else:
            if const not in consts:
                raise SystemExit(f"Nie znam stałej {const} w definicji {name}")
            out.append((name, consts[const]))
    return out


def parse_tables(src: str):
    """Czyta listę Tbl("nazwa", listOf(...), indexes = listOf(...))."""
    consts = parse_constants(src)
    tables = []
    for m in re.finditer(
        r'Tbl\(\s*"([a-z_]+)"\s*,\s*listOf\((.*?)\n\s*\)\s*,?\s*'
        r'(?:indexes\s*=\s*listOf\((.*?)\)\s*)?\)',
        src,
        re.S,
    ):
        name, cols_block, idx_block = m.group(1), m.group(2), m.group(3)
        cols = parse_col_defs(cols_block, consts)
        if not cols:
            raise SystemExit(f"Nie znalazłem kolumn tabeli {name}")
        idx = re.findall(r'"([^"]+)"', idx_block) if idx_block else []
        tables.append((name, cols, idx))
    return tables


def parse_seed_statements(src: str):
    """Inserty startowe — wszystkie poza nixi_access (tę budujemy niżej)."""
    m = re.search(r"private val seedStatements: List<String> = listOf\((.*?)\n    \)", src, re.S)
    if not m:
        return []
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
    text = "".join(kt_string(p) for p in parts)
    out = []
    for chunk in re.split(r"(?=insert into )", text):
        chunk = chunk.strip()
        if not chunk or chunk.startswith("insert into public.nixi_access"):
            continue
        out.append(chunk)
    return out


def parse_exec_function(src: str) -> str:
    m = re.search(r'private val execFunctionSql: String = """\n(.*?)\n""".trimIndent\(\)', src, re.S)
    if not m:
        raise SystemExit("Nie znalazłem execFunctionSql w DbSchema.kt")
    return kt_string(m.group(1)).rstrip()


def core_tables() -> list:
    """Kolejność tabel NIXI z Tables.kt (CORE) — do danych nixi_access."""
    src = TABLES.read_text(encoding="utf-8")
    m = re.search(r"val CORE: List<String> = listOf\((.*?)\)", src, re.S)
    if not m:
        raise SystemExit("Nie znalazłem Tables.CORE")
    names = []
    for ref in re.findall(r"\b([A-Z_]+)\b", m.group(1)):
        if ref == "CORE":
            continue
        cm = re.search(rf'const val {ref} = "([a-z_]+)"', src)
        if cm:
            names.append(cm.group(1))
    return names


def access_rows(names: list) -> str:
    """INSERT do nixi_access — te same reguły co w DbSchema.seedStatements."""
    rows = []
    for n in names:
        if n == "nixi_access":
            continue
        edit = "false" if n in NO_EDIT else "true"
        delete = "false" if n in NO_DELETE or n == "nixi_access" else "true"
        rows.append(f"('{n}', true, {edit}, {delete})")
    return (
        "insert into public.nixi_access (table_name, can_read, can_edit, can_delete) values "
        + ", ".join(rows)
        + " on conflict (table_name) do nothing"
    )


def build(src: str) -> str:
    tables = parse_tables(src)
    if len(tables) < 10:
        raise SystemExit(f"Znalazłem tylko {len(tables)} tabel — coś nie tak z DbSchema.kt")
    lines = [
        "-- ═══ NIXI: tabele (bezpieczne do wielokrotnego uruchomienia) ═══",
        "-- Plik GENEROWANY z app/src/main/java/dev/nixi/db/DbSchema.kt (tools/gen_seed_sql.py).",
        "-- Ten sam kod zakłada brakujące tabele i DOKŁADA brakujące kolumny,",
        "-- więc uruchom go ponownie po każdej aktualizacji aplikacji.",
        "-- Aplikacja umie wykonać go sama: token osobisty Supabase (sbp_…) albo",
        "-- funkcja nixi_exec_sql utworzona przy pierwszym uruchomieniu tego pliku.",
        "",
    ]
    for name, cols, idx in tables:
        lines.append(f"create table if not exists public.{name} (")
        lines.append(",\n".join(f"  {c} {d}" for c, d in cols))
        lines.append(");")
        for c, d in cols:
            # `not null` pomijamy — stare wiersze nie mają z czego go wypełnić
            lines.append(
                f"alter table public.{name} add column if not exists {c} {d.replace(' not null', '')};"
            )
        for i in idx:
            lines.append(
                f"create index if not exists {name}_{i.split(' ')[0]}_idx "
                f"on public.{name} ({i});"
            )
        lines.append(f"alter table public.{name} enable row level security;")
        lines.append(f"drop policy if exists nixi_all on public.{name};")
        lines.append(
            f"create policy nixi_all on public.{name} for all using (true) with check (true);"
        )
        lines.append("")
    lines.append("-- ── Startowe dane (nie nadpisują Twoich) ──")
    lines.extend(s + ";" for s in parse_seed_statements(src))
    lines.append(access_rows(core_tables()) + ";")
    lines.append("")
    lines.append(parse_exec_function(src))
    lines.append("")
    lines.append("-- ═══ Gotowe — wróć do aplikacji i naciśnij „Sprawdź ponownie” ═══")
    return "\n".join(lines) + "\n"


def main() -> int:
    src = SCHEMA.read_text(encoding="utf-8")
    sql = build(src)
    if "--check" in sys.argv:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != sql:
            print("supabase/seed.sql NIE zgadza się z DbSchema.kt — uruchom tools/gen_seed_sql.py")
            return 1
        print("supabase/seed.sql zgodny z DbSchema.kt")
        return 0
    OUT.write_text(sql, encoding="utf-8")
    print(f"Zapisano {OUT.relative_to(ROOT)} ({len(sql)} znaków, {sql.count(';')} poleceń)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
