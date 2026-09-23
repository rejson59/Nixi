-- ═══════════════════════════════════════════════════════════════════
--  NIXI — startowe tabele Supabase
--  Wklej całość w SQL Editor Twojego projektu Supabase.
--  RLS: aplikacja pracuje na kluczu anon, więc polityki są permissive
--  (to JEDNOSTKOWA aplikacja na Twoim telefonie — miej to na uwadze).
-- ═══════════════════════════════════════════════════════════════════

-- ── Admin (system prompt, persona, język) ─────────────────────────────
create table if not exists public.admin_table (
  key text primary key,
  value text,
  updated_at timestamptz default now()
);

insert into public.admin_table (key, value) values
  ('assistant_name', 'NIXI'),
  ('language', 'polski'),
  ('system_prompt', $$
Jesteś NIXI — osobistą asystentką głosową tego telefonu. Mówisz po polsku, ciepło, zwięźle i konkretnie (odpowiedzi głosowe: 1-3 zdania, chyba że proszą o więcej).
Zasady:
1. Wykonuj zadania narzędziami, a nie tylko mów o nich. Zawsze sprawdzaj wynik narzędzia przed odpowiedzią.
2. PRZED KAŻDYM usunięciem cokolwiek (wiersz, kalendarz, budzik, przypomnienie) zapytaj użytkownika o potwierdzenie. Aplikacja i tak pokaże okno potwierdzenia.
3. Wszystkie działania, których użytkownik nie widzi na ekranie (zapis do pamięci, ciche edycje), aplikacja zgłasza krótkim powiadomieniem — informuj o nich krótko.
4. Jeśli nie masz narzędzia do zadania albo użytkownik prosi („przejmij ekran”, „tryb ręczny”), użyj screen_manual_start i wtedy: screen_get (zobacz ekran), screen_tap / screen_swipe / screen_text, i screen_get po każdym ruchu, dopóki zadanie się nie zakończy. Kończ tryb ręczny przez screen_manual_stop.
5. Wspomnienia: trwałe fakty o użytkowniku zapisuj przez memory_store. Ostatnie fakty i kontekst znajdziesz w instrukcji systemu — używaj ich naturalnie.
6. Cytaty, plan dnia, lekcje, rutyny — bierz z danych (kalendarz, lesson_plan, routines), nie zgaduj.
7. Bądź naturalna: nazywasz użytkownika per „Ty” lub imieniem jeśli je znasz. Nie używaj emoji ani znaków specjalnych w mowie.
$$)
on conflict (key) do update set value = excluded.value;

-- ── Użytkownik ─────────────────────────────────────────────────────────
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

-- ── Pamięć długotrwała (fakty) ─────────────────────────────────────────
create table if not exists public.memory_facts (
  key text primary key,
  value text,
  category text default 'ogólne',
  updated_at timestamptz default now()
);

-- ── Ostatnie ciekawe rozmowy (podsumowania) ───────────────────────────
create table if not exists public.recent_conversations (
  id bigint generated always as identity primary key,
  summary text,
  topics text,
  duration_sec int,
  created_at timestamptz default now()
);

-- ── Przypomnienia ──────────────────────────────────────────────────────
create table if not exists public.reminders (
  id bigint generated always as identity primary key,
  title text not null,
  fires_at timestamptz,
  done boolean default false,
  note text,
  created_at timestamptz default now()
);

-- ── Budziki (lustro alarmów systemowych) ──────────────────────────────
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

-- ── Kalendarz (wbudowany, połączony z Supabase) ───────────────────────
create table if not exists public.calendar_events (
  id bigint generated always as identity primary key,
  title text not null,
  start timestamptz not null,
  end timestamptz,
  location text,
  notes text,
  kind text default 'normal', -- normal | zastepstwo | wolne
  created_at timestamptz default now()
);

-- ── Plan lekcji ────────────────────────────────────────────────────────
create table if not exists public.lesson_plan (
  id bigint generated always as identity primary key,
  day_of_week smallint not null, -- 1=pon .. 7=ndz (Postgres)
  start time not null,
  end time,
  subject text,
  room text,
  teacher text,
  term text default 'bieżący'
);

-- ── Rutyny (np. „Dzień dobry” rano → godzina + cytat + lekcje) ───────
create table if not exists public.routines (
  id bigint generated always as identity primary key,
  name text,
  trigger text not null,
  time_of_day text, -- rano | po południu | wieczorem | null
  steps text not null, -- co NIXI ma zrobić krok po kroku
  active boolean default true
);

insert into public.routines (name, trigger, time_of_day, steps) values
  ('Rano', 'Dzień dobry', 'rano',
   'Powiedz jaką jest godzinę, podaj krótki cytat podnoszący na duchu, powiedz o której zaczyna się pierwsza lekcja (z lesson_plan/kalendarza) i życzą udanego dnia.')
on conflict do nothing;

-- ── Ciche reguły powiadomień ──────────────────────────────────────────
create table if not exists public.silent_rules (
  id bigint generated always as identity primary key,
  name text,
  app_package text,
  contains text,
  action jsonb default '{}'::jsonb,
  active boolean default true
);

-- Przykład: informacja o zastępstwie z dziennika -> cicho zmienia kalendarz.
-- Uzupełnij app_package pakietem aplikacji dziennika (np. pl.edu... ).
insert into public.silent_rules (name, app_package, contains, action, active) values
  ('Zastępstwo z dziennika', '', 'zastępstwo',
   '{"type": "calendar_substitution"}'::jsonb, true);

-- ── Ustawienia (klucz/wartość JSON) ───────────────────────────────────
create table if not exists public.settings (
  key text primary key,
  value jsonb,
  updated_at timestamptz default now()
);

-- ── Logi systemowe (akcje NIXI / tool calling) ────────────────────────
create table if not exists public.system_logs (
  id bigint generated always as identity primary key,
  ts bigint not null,
  action text,
  detail text,
  status text default 'ok'
);

create index if not exists system_logs_ts_idx on public.system_logs (ts desc);

-- ── Błędy (do ulepszania aplikacji) ───────────────────────────────────
create table if not exists public.errors (
  id bigint generated always as identity primary key,
  ts bigint not null,
  tag text,
  message text
);

create index if not exists errors_ts_idx on public.errors (ts desc);

-- ── Dostęp NIXI do tabel (C/E/D) ──────────────────────────────────────
create table if not exists public.nixi_access (
  table_name text primary key,
  can_read boolean default true,
  can_edit boolean default true,
  can_delete boolean default false
);

insert into public.nixi_access (table_name, can_read, can_edit, can_delete) values
  ('admin_table', true, true, false),
  ('users', true, true, false),
  ('memory_facts', true, true, true),
  ('recent_conversations', true, true, true),
  ('reminders', true, true, true),
  ('alarms', true, true, true),
  ('calendar_events', true, true, true),
  ('lesson_plan', true, true, true),
  ('routines', true, true, true),
  ('silent_rules', true, true, true),
  ('settings', true, true, false),
  ('system_logs', true, false, true),
  ('errors', true, false, true)
on conflict (table_name) do nothing;

-- ── RLS: włączamy i otwieramy dla anon (osobisty projekt) ────────────
do $$
declare t text;
begin
  foreach t in array array[
    'admin_table','users','memory_facts','recent_conversations','reminders',
    'alarms','calendar_events','lesson_plan','routines','silent_rules',
    'settings','system_logs','errors','nixi_access'
  ] loop
    execute format('alter table public.%I enable row level security', t);
    execute format(
      'create policy nixi_all on public.%I for all using (true) with check (true)',
      t
    );
  end loop;
end $$;

-- =============================================================
-- Gotowe! Otwórz aplikację NIXI i wklej URL + klucz anon.
-- Nowe tabele, które stworzysz później, zostaną
-- automatycznie odkryte przez aplikację (OpenAPI PostgREST).
-- =============================================================
