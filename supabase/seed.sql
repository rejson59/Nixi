-- ═══ NIXI: tabele (bezpieczne do wielokrotnego uruchomienia) ═══
-- Plik GENEROWANY z app/src/main/java/dev/nixi/db/DbSchema.kt (tools/gen_seed_sql.py).
-- Ten sam kod zakłada brakujące tabele i DOKŁADA brakujące kolumny,
-- więc uruchom go ponownie po każdej aktualizacji aplikacji.
-- Aplikacja umie wykonać go sama: token osobisty Supabase (sbp_…) albo
-- funkcja nixi_exec_sql utworzona przy pierwszym uruchomieniu tego pliku.

create table if not exists public.admin_table (
  key text primary key,
  value text,
  updated_at timestamptz default now()
);
alter table public.admin_table add column if not exists key text primary key;
alter table public.admin_table add column if not exists value text;
alter table public.admin_table add column if not exists updated_at timestamptz default now();
alter table public.admin_table enable row level security;
drop policy if exists nixi_all on public.admin_table;
create policy nixi_all on public.admin_table for all using (true) with check (true);

create table if not exists public.users (
  id bigint generated always as identity primary key,
  display_name text,
  name text,
  birth_date date,
  city text,
  email text,
  notes text,
  schedule_notes text,
  created_at timestamptz default now()
);
alter table public.users add column if not exists id bigint generated always as identity primary key;
alter table public.users add column if not exists display_name text;
alter table public.users add column if not exists name text;
alter table public.users add column if not exists birth_date date;
alter table public.users add column if not exists city text;
alter table public.users add column if not exists email text;
alter table public.users add column if not exists notes text;
alter table public.users add column if not exists schedule_notes text;
alter table public.users add column if not exists created_at timestamptz default now();
alter table public.users enable row level security;
drop policy if exists nixi_all on public.users;
create policy nixi_all on public.users for all using (true) with check (true);

create table if not exists public.memory_facts (
  key text primary key,
  value text,
  category text default 'ogólne',
  updated_at timestamptz default now()
);
alter table public.memory_facts add column if not exists key text primary key;
alter table public.memory_facts add column if not exists value text;
alter table public.memory_facts add column if not exists category text default 'ogólne';
alter table public.memory_facts add column if not exists updated_at timestamptz default now();
alter table public.memory_facts enable row level security;
drop policy if exists nixi_all on public.memory_facts;
create policy nixi_all on public.memory_facts for all using (true) with check (true);

create table if not exists public.recent_conversations (
  id bigint generated always as identity primary key,
  summary text,
  topics text,
  duration_sec int,
  created_at timestamptz default now()
);
alter table public.recent_conversations add column if not exists id bigint generated always as identity primary key;
alter table public.recent_conversations add column if not exists summary text;
alter table public.recent_conversations add column if not exists topics text;
alter table public.recent_conversations add column if not exists duration_sec int;
alter table public.recent_conversations add column if not exists created_at timestamptz default now();
alter table public.recent_conversations enable row level security;
drop policy if exists nixi_all on public.recent_conversations;
create policy nixi_all on public.recent_conversations for all using (true) with check (true);

create table if not exists public.reminders (
  id bigint generated always as identity primary key,
  title text not null,
  fires_at timestamptz,
  done boolean default false,
  note text,
  created_at timestamptz default now()
);
alter table public.reminders add column if not exists id bigint generated always as identity primary key;
alter table public.reminders add column if not exists title text;
alter table public.reminders add column if not exists fires_at timestamptz;
alter table public.reminders add column if not exists done boolean default false;
alter table public.reminders add column if not exists note text;
alter table public.reminders add column if not exists created_at timestamptz default now();
alter table public.reminders enable row level security;
drop policy if exists nixi_all on public.reminders;
create policy nixi_all on public.reminders for all using (true) with check (true);

create table if not exists public.alarms (
  id bigint generated always as identity primary key,
  time time not null,
  label text,
  ring_on_weekends boolean default true,
  sound text,
  active boolean default true,
  created_by_nixi boolean default true,
  created_at timestamptz default now()
);
alter table public.alarms add column if not exists id bigint generated always as identity primary key;
alter table public.alarms add column if not exists time time;
alter table public.alarms add column if not exists label text;
alter table public.alarms add column if not exists ring_on_weekends boolean default true;
alter table public.alarms add column if not exists sound text;
alter table public.alarms add column if not exists active boolean default true;
alter table public.alarms add column if not exists created_by_nixi boolean default true;
alter table public.alarms add column if not exists created_at timestamptz default now();
alter table public.alarms enable row level security;
drop policy if exists nixi_all on public.alarms;
create policy nixi_all on public.alarms for all using (true) with check (true);

create table if not exists public.calendar_events (
  id bigint generated always as identity primary key,
  title text not null,
  start timestamptz not null,
  end timestamptz,
  location text,
  notes text,
  kind text default 'normal',
  manual boolean default false,
  created_at timestamptz default now()
);
alter table public.calendar_events add column if not exists id bigint generated always as identity primary key;
alter table public.calendar_events add column if not exists title text;
alter table public.calendar_events add column if not exists start timestamptz;
alter table public.calendar_events add column if not exists end timestamptz;
alter table public.calendar_events add column if not exists location text;
alter table public.calendar_events add column if not exists notes text;
alter table public.calendar_events add column if not exists kind text default 'normal';
alter table public.calendar_events add column if not exists manual boolean default false;
alter table public.calendar_events add column if not exists created_at timestamptz default now();
alter table public.calendar_events enable row level security;
drop policy if exists nixi_all on public.calendar_events;
create policy nixi_all on public.calendar_events for all using (true) with check (true);

create table if not exists public.lesson_plan (
  id bigint generated always as identity primary key,
  day_of_week smallint not null,
  start time not null,
  end time,
  subject text,
  room text,
  teacher text,
  term text default 'bieżący'
);
alter table public.lesson_plan add column if not exists id bigint generated always as identity primary key;
alter table public.lesson_plan add column if not exists day_of_week smallint;
alter table public.lesson_plan add column if not exists start time;
alter table public.lesson_plan add column if not exists end time;
alter table public.lesson_plan add column if not exists subject text;
alter table public.lesson_plan add column if not exists room text;
alter table public.lesson_plan add column if not exists teacher text;
alter table public.lesson_plan add column if not exists term text default 'bieżący';
alter table public.lesson_plan enable row level security;
drop policy if exists nixi_all on public.lesson_plan;
create policy nixi_all on public.lesson_plan for all using (true) with check (true);

create table if not exists public.routines (
  id bigint generated always as identity primary key,
  name text,
  trigger text not null,
  time_of_day text,
  steps text not null,
  active boolean default true
);
alter table public.routines add column if not exists id bigint generated always as identity primary key;
alter table public.routines add column if not exists name text;
alter table public.routines add column if not exists trigger text;
alter table public.routines add column if not exists time_of_day text;
alter table public.routines add column if not exists steps text;
alter table public.routines add column if not exists active boolean default true;
alter table public.routines enable row level security;
drop policy if exists nixi_all on public.routines;
create policy nixi_all on public.routines for all using (true) with check (true);

create table if not exists public.silent_rules (
  id bigint generated always as identity primary key,
  name text,
  app_package text,
  contains text,
  action jsonb default '{}'::jsonb,
  active boolean default true
);
alter table public.silent_rules add column if not exists id bigint generated always as identity primary key;
alter table public.silent_rules add column if not exists name text;
alter table public.silent_rules add column if not exists app_package text;
alter table public.silent_rules add column if not exists contains text;
alter table public.silent_rules add column if not exists action jsonb default '{}'::jsonb;
alter table public.silent_rules add column if not exists active boolean default true;
alter table public.silent_rules enable row level security;
drop policy if exists nixi_all on public.silent_rules;
create policy nixi_all on public.silent_rules for all using (true) with check (true);

create table if not exists public.settings (
  key text primary key,
  value jsonb,
  updated_at timestamptz default now()
);
alter table public.settings add column if not exists key text primary key;
alter table public.settings add column if not exists value jsonb;
alter table public.settings add column if not exists updated_at timestamptz default now();
alter table public.settings enable row level security;
drop policy if exists nixi_all on public.settings;
create policy nixi_all on public.settings for all using (true) with check (true);

create table if not exists public.system_logs (
  id bigint generated always as identity primary key,
  ts bigint not null,
  action text,
  detail text,
  status text default 'ok'
);
alter table public.system_logs add column if not exists id bigint generated always as identity primary key;
alter table public.system_logs add column if not exists ts bigint;
alter table public.system_logs add column if not exists action text;
alter table public.system_logs add column if not exists detail text;
alter table public.system_logs add column if not exists status text default 'ok';
create index if not exists system_logs_ts_idx on public.system_logs (ts desc);
alter table public.system_logs enable row level security;
drop policy if exists nixi_all on public.system_logs;
create policy nixi_all on public.system_logs for all using (true) with check (true);

create table if not exists public.errors (
  id bigint generated always as identity primary key,
  ts bigint not null,
  tag text,
  message text
);
alter table public.errors add column if not exists id bigint generated always as identity primary key;
alter table public.errors add column if not exists ts bigint;
alter table public.errors add column if not exists tag text;
alter table public.errors add column if not exists message text;
create index if not exists errors_ts_idx on public.errors (ts desc);
alter table public.errors enable row level security;
drop policy if exists nixi_all on public.errors;
create policy nixi_all on public.errors for all using (true) with check (true);

create table if not exists public.nixi_access (
  table_name text primary key,
  can_read boolean default true,
  can_edit boolean default true,
  can_delete boolean default false
);
alter table public.nixi_access add column if not exists table_name text primary key;
alter table public.nixi_access add column if not exists can_read boolean default true;
alter table public.nixi_access add column if not exists can_edit boolean default true;
alter table public.nixi_access add column if not exists can_delete boolean default false;
alter table public.nixi_access enable row level security;
drop policy if exists nixi_all on public.nixi_access;
create policy nixi_all on public.nixi_access for all using (true) with check (true);

create table if not exists public.todos (
  id bigint generated always as identity primary key,
  title text not null,
  done boolean default false,
  category text default 'ogólne',
  due timestamptz,
  created_at timestamptz default now()
);
alter table public.todos add column if not exists id bigint generated always as identity primary key;
alter table public.todos add column if not exists title text;
alter table public.todos add column if not exists done boolean default false;
alter table public.todos add column if not exists category text default 'ogólne';
alter table public.todos add column if not exists due timestamptz;
alter table public.todos add column if not exists created_at timestamptz default now();
alter table public.todos enable row level security;
drop policy if exists nixi_all on public.todos;
create policy nixi_all on public.todos for all using (true) with check (true);

create table if not exists public.shopping (
  id bigint generated always as identity primary key,
  item text not null,
  qty text,
  done boolean default false,
  created_at timestamptz default now()
);
alter table public.shopping add column if not exists id bigint generated always as identity primary key;
alter table public.shopping add column if not exists item text;
alter table public.shopping add column if not exists qty text;
alter table public.shopping add column if not exists done boolean default false;
alter table public.shopping add column if not exists created_at timestamptz default now();
alter table public.shopping enable row level security;
drop policy if exists nixi_all on public.shopping;
create policy nixi_all on public.shopping for all using (true) with check (true);

create table if not exists public.people (
  id bigint generated always as identity primary key,
  name text not null,
  relation text,
  notes text,
  last_talk timestamptz,
  created_at timestamptz default now()
);
alter table public.people add column if not exists id bigint generated always as identity primary key;
alter table public.people add column if not exists name text;
alter table public.people add column if not exists relation text;
alter table public.people add column if not exists notes text;
alter table public.people add column if not exists last_talk timestamptz;
alter table public.people add column if not exists created_at timestamptz default now();
alter table public.people enable row level security;
drop policy if exists nixi_all on public.people;
create policy nixi_all on public.people for all using (true) with check (true);

-- ── Startowe dane (nie nadpisują Twoich) ──
insert into public.admin_table (key, value) values ('assistant_name', 'NIXI') on conflict (key) do nothing;
insert into public.admin_table (key, value) values ('language', 'polski') on conflict (key) do nothing;
insert into public.routines (name, trigger, time_of_day, steps) values ('Rano', 'Dzień dobry', 'rano', 'Powiedz którą jest godzinę, podaj krótki cytat podnoszący na duchu, powiedz o której zaczyna się pierwsza lekcja (z lesson_plan/kalendarza) i życz udanego dnia.');
insert into public.silent_rules (name, app_package, contains, action, active) values ('Zastępstwo z dziennika', '', 'zastępstwo', '{"type": "calendar_substitution"}'::jsonb, true);
insert into public.silent_rules (name, app_package, contains, action, active) values ('Zastępstwo eduVulcan', 'pl.edu.vulcan.hebe', 'zastępstwo', '{"type": "calendar_substitution"}'::jsonb, true);
insert into public.silent_rules (name, app_package, contains, action, active) values ('Sprawdzian z dziennika', '', 'sprawdzian', '{"type": "calendar_test"}'::jsonb, true);
insert into public.silent_rules (name, app_package, contains, action, active) values ('Wolne z dziennika', '', 'wolne', '{"type": "calendar_day_off"}'::jsonb, true);
insert into public.nixi_access (table_name, can_read, can_edit, can_delete) values ('admin_table', true, true, false), ('users', true, true, false), ('recent_conversations', true, true, true), ('memory_facts', true, true, true), ('reminders', true, true, true), ('alarms', true, true, true), ('calendar_events', true, true, true), ('lesson_plan', true, true, true), ('routines', true, true, true), ('silent_rules', true, true, true), ('settings', true, true, false), ('system_logs', true, false, true), ('errors', true, false, true), ('todos', true, true, true), ('shopping', true, true, true), ('people', true, true, true) on conflict (table_name) do nothing;

-- ── Furtka dla aplikacji: wykonywanie TYLKO poleceń NIXI ──────────────
create or replace function public.nixi_exec_sql(sql text)
returns void
language plpgsql
security definer
set search_path = public
as $body$
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
  if sql like '%$$%' then
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
end $body$;

revoke all on function public.nixi_exec_sql(text) from public;
grant execute on function public.nixi_exec_sql(text) to anon, authenticated;

-- ═══ Gotowe — wróć do aplikacji i naciśnij „Sprawdź ponownie” ═══
