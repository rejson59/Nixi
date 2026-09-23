# NIXI — asystentka AI na telefonie (Android, Kotlin)

NIXI to głosowa asystentka AI działająca **w tle Twojego telefonu**: budzisz ją
frazą **„Hej Nixi”** (wykrywanie 100% offline), a ona rozmawia przez **Gemini
Live API** i realnie działa — muzyka (Spotify), kalendarz, budziki,
przypomnienia, tabele Supabase, ciche reguły powiadomień, a nawet **tryb
ręczny**, w którym widzi Twój ekran i klika w nim zamiast Ciebie.

---

## Funkcje

| Obszar | Co potrafi |
|---|---|
| **Mózg** | Gemini `gemini-3.8-live` (Live API, WebSocket). Klucz API wklejasz w aplikacji. Klient ma wbudowaną **ochronę TPM** (65K/min free tier) — nigdy nie wysyła audio ponad budżet tokenów i łagodnie pauzuje przy 429. |
| **Pamięć długotrwała** | Po każdej rozmowie NIXI (tani model flash) wyciąga **trwałe fakty** (`memory_facts`) i krótkie **podsumowanie** (`recent_conversations`). Fakty są wstrzykiwane w system prompt kolejnych sesji. |
| **Wake-word** | „Hej Nixi” — **offline**, bez modeli i sieci: cechy mel (Goertzel) + adaptacyjny szum + DTW przeciwko Twojemu 3-krotnemu nagraniu. Niskie zużycie: jeden wspólny `AudioRecord`, brak wakelocka, tryb ECO. Działa w tle i **nad ekranem blokady**. |
| **Tło** | Foreground service (typ `microphone`) z `START_STICKY`, restart po boot, pauza multimediów na start rozmowy (MediaController + Spotify API), wznowienie po zakończeniu. |
| **Przezroczystość** | Każde niewidoczne działanie (zapis do pamięci, ciche reguły, edycje w tle, DDL) → **krótkie powiadomienie**. |
| **Supabase** | Pełny CRUD tabel/wierszy z aplikacji i głosem. **Uniwersalność**: aplikacja odkrywa każdą tabelę przez OpenAPI PostgREST — nowa tabela = automatycznie widoczna. Matrixa dostępu NIXI (`nixi_access`: czyta/edytuje/usuwa). Usuwanie **zawsze z potwierdzeniem** (okno Tak/Nie). Nowe tabele/kolumny (DDL): NIXI generuje SQL, Ty wklejasz w SQL Editorze (jedno tapnięcie). |
| **Spotify** | Device-flow OAuth (wklejasz tylko Client ID). Co gra, pauza/wznowienie, następny/previous, przewijanie, głośność, odtwarzanie utworów/playlist/albumów, lista playlist. |
| **Budziki** | Ustawianie systemowych alarmów (intent `AlarmClock`, na 12+ bez UI), lista/edycja lustra w `alarms`. Uwaga: Android nie udostępnia API usuwania cudzych alarmów — NIXI wyłącza swoje w lustrze i otwiera listę alarmów do zdjęcia w zegarze (mówi o tym głośno). |
| **Kalendarz** | Wbudowany (tabela `calendar_events`), bez Google/systemowego: lista, dodawanie, edycja, usuwanie (z potwierdzeniem), **zastępstwa** jednym słowem. |
| **Powiadomienia** | Odczyt na żądanie (`notifications_read`) + **ciche reguły** (`silent_rules`), np. przychodzi SMS z dziennika o zastępstwie → NIXI cicho zmienia wpis w kalendarzu i krótko raportuje powiadomieniem. |
| **Tryb ręczny** | Gdy nie ma narzędzia (lub prosisz: „przejmij ekran”): NIXI poprosi o zgodę MediaProjection, **widzi ekran** (zrzuty → Gemini, który je rozumie) i klika/scrolluje/wpisuje przez AccessibilityService, dopóki nie dokończy zadania. |
| **UI** | Animowana **kula NIXI** (port „Pulse Engine” na Compose) — rośnie od głośności głosu, slide-in w rogu ekranu przy wywołaniu (także nad blokadką), lekki dźwięk „mów”. Szybki onboarding, rozbudowane ustawienia (głos, czułość, ECO, TPM, integracje), przeglądarka tabel, logi. |

---

## Budowanie APK (GitHub Actions)

Repozytorium zawiera workflow **`.github/workflows/build-apk.yml`**:

1. Push na `main` (lub branch `arena/**`) → job buduje **`debug` i `release` APK**.
2. Pliki pobierzesz w zakładce **Actions → wybrany run → artefakt `NIXI-APK-…`**.
3. Tag `v1.0.0` → auto **GitHub Release** z APK w plikach.

Lokalnie (opcjonalnie):

```bash
gradle wrapper            # jednorazowo, jeśli chcesz używać ./gradlew
./gradlew assembleRelease
```

Wymagania: JDK 17, Gradle 8.10+ (workflow sam ustawia oba).

---

## Start (5 minut)

1. **Klucz Gemini** — aistudio.google.com/apikey (darmowy plan; model `gemini-3.8-live`
   musi być dostępny dla Twojego klucza — na free tierze Live ma 65K TPM, 10 RPM, 250 RPD).
2. **Supabase** — utwórz projekt, wklej `supabase/seed.sql` w SQL Editorze,
   skopiuj **URL projektu** i **klucz anon**.
3. Zainstaluj APK, uruchom, przejdź 6-krokowy onboarding:
   klucz Gemini → Supabase → **3× „Hej Nixi”** (rejestracja) → uprawnienia → głos.
4. Gotowe: powiedz **„Hej Nixi”** (muzyka się zatrzyma, kula wjedzie w róg ekranu).

### Przydatne zdania na start

- „Hej Nixi, co mam dzisiaj?” (kalendarz + lekcje)
- „Hej Nixi, dodaj przypomnienie o spacerze z psem o 17:30”
- „Hej Nixi, ustaw budzik na 6:45, etykieta wstawa”
- „Hej Nixi, zastępstwo z matematyki” (jeśli masz regułę lub powiedz „oznacz matematykę jako zastępstwo”)
- „Hej Nixi, ile wierszy jest w tabeli calendar_events?”
- „Hej Nixi, otwórz tabelę users i pokaż pierwszy wiersz”
- „Hej Nixi, włącz tryb ręczny i otwórz przeglądarkę” (poprosi o zgodę na podgląd)

---

## Architektura (skrót)

```
dev.nixi/
├─ NixiApp, NixiState          # stan globalny (kula, sesja, pending akcji)
├─ audio/                      # AudioBus (jeden mikrofon dla całości),
│                               # AudioPlayer (24 kHz, RMS→kula), MediaPauseController
├─ wake/                       # WakeEngine (offline DTW), WakeWordService, EnrollmentController
├─ live/                       # GeminiLiveClient (WS BidiGenerateContent),
│                               # LiveSessionService (sesja + narzędzia + TPM),
│                               # SystemPromptBuilder, TpmGuard, PostSessionMemory
├─ tools/                      # rejestr + implementacje narzędzi (Supabase, Spotify,
│                               # kalendarz, budziki, przypomnienia, powiadomienia, ekran, system)
├─ db/                         # PostgrestClient (cienki REST + OpenAPI discover), SupabaseHub
├─ notif/                      # ActionNotifier (raporty), NotificationListener (ciche reguły),
│                               # ReminderReceiver (dokładne alarmy)
├─ screen/ + accessibility/    # ScreenCaptureService (MediaProjection),
│                               # NixiAccessibilityService (gesty tap/swipe)
├─ overlay/ConversationActivity# okno z kulą nad blokadką (potwierdzenia, tryb ręczny, SQL)
└─ ui/                         # Compose: onboarding, home, ustawienia, tabele, logi
```

### Ochrona TPM (po stronie klienta)

`TpmGuard` utrzymuje okno 60 s z szacunkiem tokenów (audio ~25 tok/s, tekst
¼ znaku, obraz px/750). Domyślny limit **55K tok/min** (bufor pod 65K).
Gdy okno się zapełnia: NIXI **przestaje nadawać audio** (stan `TPM_LIMIT` na
kuli), nie zrywa sesji; po 429 od Google — backoff 60 s i powiadomienie.
Tryb `eco` = 45K.

### Wake-word — jak działa offline

1. PCM 16 kHz → klatki 20 ms (hop 10/20 ms),
2. 16 pasm mel (Goertzel) → cecha = log-wzrost nad adaptacyjną podłogą szumu,
3. bramka „czy padła mowa” — DTW (Sakoe-Chiba) uruchamia się **tylko wtedy**,
4. dopasowanie do szablonu z Twojej 3-krotnej rejestracji, próg kalibrowany
   z odległości między próbami,
5. cooldown 12 s, wyzwanie → pauza multimediów → sesja Live → kula w rogu.

W ustawieniach: czułość 0–100%, tryb ECO (8 pasm, hop 20 ms) i statystyki
`ms CPU / minutę nasłuchu`.

---

## Uprawnienia i dlaczego

| Uprawnienie | Do czego |
|---|---|
| Mikrofon (FGS) | nasłuch „Hej Nixi” + rozmowa |
| Powiadomienia | raporty o akcjach + przypomnienia |
| Dostęp do powiadomień | odczyt + ciche reguły (opcjonalne) |
| Usługa dostępności | tap/scroll w trybie ręcznym (opcjonalne) |
| Dokładne alarmy | przypomnienia o czasie (opcjonalne) |
| MediaProjection (konsent) | zrzuty ekranu w trybie ręcznym (tylko na czas sesji) |

Nie wymagamy administratora urządzenia, SMS-ów ani lokalizacji.
Możesz także ustawić NIXI jako domyślnego asystenta (opcjonalne — wake-word
i tak działa niezależnie od wywołania długim przytrzymaniem).

## Ograniczenia (uczciwie)

- **Budziki**: Android nie daje API odczytu/usuwania alarmów z zegara — NIXI
  zarządza alarmami, które sama ustawiła (lustro w `alarms`); do zdjęcia
  ostatniego otwiera listę alarmów.
- **DDL** (nowe tabele/kolumny): PostgREST nie udostępnia SQL — NIXI i
  aplikacja **generują i pokazują SQL**, a Ty wklejasz w SQL Editorze
  (deep-link jednym tapnięciem). Wiersze: pełny CRUD.
- **Spotify**: wymaga Client ID z developer.spotify.com (device flow,
  bez sekretu w aplikacji).
- **Rutyny**: wstrzykiwane w prompt (trigger → kroki) — działa na rozmowie;
  ciche reguły powiadomień działają niezależnie.

## Bezpieczeństwo

- Klucze (Gemini, Supabase anon, Spotify token) trzymasz **lokalnie**
  w prywatnych SharedPreferences urządzenia.
- RLS w seed.sql jest permissive, bo to osobisty projekt — nie udostępniaj
  klucza anon publicznie.
- Tryb ręczny uruchamia się **tylko** z Twojej zgody (systemowy konsent)
  i tylko na czas sesji.
