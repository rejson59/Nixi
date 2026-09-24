# NIXI — asystentka AI na telefonie (Android, Kotlin)

NIXI to głosowa asystentka AI działająca **w tle Twojego telefonu**: budzisz ją
frazą **„Hej Nixi”** (wykrywanie 100% offline), a ona rozmawia przez **Gemini
Live API** i realnie działa — muzyka (Spotify), kalendarz, budziki,
przypomnienia, tabele Supabase, ciche reguły powiadomień, a nawet **tryb
ręczny**, w którym widzi Twój ekran i klika w nim zamiast Ciebie.

Projekt jest zoptymalizowany pod **Xiaomi Redmi Note 14 Pro 5G (HyperOS)** —
patrz sekcja [Praca w tle na Xiaomi](#praca-w-tle-na-xiaomi--hyperos), bo bez
trzech ustawień systemowych żadna aplikacja z mikrofonem w tle nie przetrwa.

---

## Funkcje

| Obszar | Co potrafi |
|---|---|
| **Mózg** | Gemini Live API (WebSocket `BidiGenerateContent`). Klucz API wklejasz w aplikacji. Klient ma wbudowaną **ochronę TPM** (65K/min free tier) — liczy wejście **i wyjście** modelu, nigdy nie wysyła audio ponad budżet i łagodnie pauzuje przy 429. Sesja **wznawia się sama** po zerwaniu sieci i po komunikacie `goAway` (session resumption, 3 próby z backoffem). |
| **Pamięć długotrwała** | Po każdej rozmowie NIXI (tani model flash) wyciąga **trwałe fakty** (`memory_facts`) i krótkie **podsumowanie** (`recent_conversations`). Fakty są wstrzykiwane w system prompt kolejnych sesji. |
| **Wake-word** | „Hej Nixi” — **offline**, bez modeli i sieci: cechy mel (Goertzel) + adaptacyjny szum + DTW przeciwko Twojemu 3-krotnemu nagraniu. Niskie zużycie: jeden wspólny `AudioRecord`, brak wakelocka, tryb ECO. Działa w tle i **nad ekranem blokady**. |
| **Tło** | Foreground service (typ `microphone`) z `START_STICKY`; po restarcie telefonu (i po aktualizacji aplikacji) nasłuch wznawia się sam, a **przypomnienia są odtwarzane** z bazy (AlarmManager czyści alarmy przy restarcie). |
| **Odporność mikrofonu** | Jeśli mikrofon zabierze rozmowa telefoniczna albo inna aplikacja, NIXI **nie gubi nasłuchu** — odkłada próbę i wraca do pracy (backoff, raport na ekranie głównym). |
| **Przezroczystość** | Każde niewidoczne działanie (zapis do pamięci, ciche reguły, edycje w tle, DDL) → **krótkie powiadomienie**. |
| **Supabase** | Pełny CRUD tabel/wierszy z aplikacji i głosem. **Uniwersalność**: aplikacja odkrywa każdą tabelę przez OpenAPI PostgREST — nowa tabela = automatycznie widoczna. Matrixa dostępu NIXI (`nixi_access`: czyta/edytuje/usuwa). Usuwanie **zawsze z potwierdzeniem** (okno Tak/Nie, a gdy okno rozmowy jest schowane — powiadomienie). Nowe tabele/kolumny (DDL): NIXI generuje SQL, Ty wklejasz w SQL Editorze (jedno tapnięcie). |
| **Spotify** | Authorization Code + PKCE (wklejasz tylko Client ID). Co gra, pauza/wznowienie, następny/previous, przewijanie, głośność, odtwarzanie utworów/playlist/albumów, lista playlist. |
| **Budziki** | Ustawianie systemowych alarmów (intent `AlarmClock`, na 12+ bez UI), lista/edycja lustra w `alarms`. Uwaga: Android nie udostępnia API usuwania cudzych alarmów — NIXI wyłącza swoje w lustrze i otwiera listę alarmów do zdjęcia w zegarze (mówi o tym głośno). |
| **Kalendarz** | Wbudowany (tabela `calendar_events`), bez Google/systemowego: lista, dodawanie, edycja, usuwanie (z potwierdzeniem), **zastępstwa** jednym słowem. |
| **Powiadomienia** | Odczyt na żądanie (`notifications_read`) + **ciche reguły** (`silent_rules`), np. przychodzi SMS z dziennika o zastępstwie → NIXI cicho zmienia wpis w kalendarzu i krótko raportuje powiadomieniem. Reguły odświeżają się w tle co 10 minut. |
| **Multimedia** | Start rozmowy pauzuje muzykę/podcasty (przez aktywne sesje multimediów), a koniec sesji wznawia **tylko to, co NIXI sama zatrzymała**. |
| **Tryb ręczny** | Gdy nie ma narzędzia (lub prosisz: „przejmij ekran”): NIXI poprosi o zgodę MediaProjection, **widzi ekran** (zrzuty → Gemini, który je rozumie) i klika/scrolluje/wpisuje przez AccessibilityService, dopóki nie dokończy zadania. |
| **UI** | Animowana **kula NIXI** (Compose) — rośnie od głośności głosu, wjeżdża w róg ekranu przy wywołaniu (także nad blokadką), z licznikiem zużycia tokenów. Szybki onboarding, rozbudowane ustawienia (głos, czułość, ECO, TPM, integracje, praca w tle), przeglądarka tabel, logi. |

---

## Co poprawiono w tej wersji (stabilizacja)

Ta wersja nie dodaje nowych „modułów” — porządkuje i utwardza to, co już było.

**Krytyczne (potrafiły zabić proces albo uszkodzić funkcję):**

- `AudioPlayer` przerywał własny wątek i kończył sesję **wyjątkiem `InterruptedException`** poza wątkiem głównym → aplikacja mogła się wywalić przy każdym zamknięciu rozmowy. Teraz odtwarzacz kończy pracę bez przerywania wątków i zawsze zwalnia `AudioTrack`.
- `AudioBus` (nasłuch) **umierał na zawsze** przy pierwszym błędzie `AudioRecord.read()` — teraz odtwarza rekord z backoffem, rozpoznaje trwającą rozmowę telefoniczną i po 5 nieudanych próbach melduje problem w UI.
- Start usługi nasłuchu z tła (m.in. po restarcie telefonu na Androidzie 12+ / HyperOS) rzucał `ForegroundServiceStartNotAllowedException` → **crash o wschodzie słońca**. Teraz jest łapany, a użytkownik dostaje powiadomienie z akcją wznowienia.
- Po rejestracji „Hej Nixi” w Ustawieniach nasłuch **gasł do restartu aplikacji** (wspólny mikrofon był zamykany i nikt go nie otwierał). Teraz mikrofon wraca tam, gdzie ma wrócić.
- Po restarcie telefonu i po „wymuszonym zatrzymaniu” (HyperOS) **przypomnienia nie dzwoniły** — teraz są odtwarzane z `reminders` (BootReceiver + powrót do aplikacji).
- Ekrany **Tabele** i **Logi** wołały zapytania sieciowe **w ciele kompozycji** → pętla zapytań przy każdym przeliczeniu UI. Przeniesione do `LaunchedEffect` (jedno pobranie na zmianę danych), dodana poprawna paginacja (bez duplikatów wierszy).
- `db_ddl` (propozycja SQL) **zawsze kończył się błędem** — regex był sprawdzany przez `matches()` (pełne dopasowanie całego stringa), więc żadne `CREATE TABLE …` nie przechodziło.
- Licznik bezczynności sesji nie działał (poziom mikrofonu odświeżał go 15×/s), więc rozmowa nigdy nie kończyła się sama i trzymała mikrofon. Teraz liczy realną aktywność użytkownika (5 min bezczynności = koniec sesji + ostrzeżenie po minucie ciszy).
- Wysyłaliśmy do Gemini Live **nieistniejące komunikaty klienta** (`interrupt`, `goAway`, `sessionUpdate`) → serwer odpowiadał błędem protokołu. Usunięte; przerwanie odpowiedzi robi się lokalnie (`flush` odtwarzacza + automatyczne VAD).
- `TimeUtils` dzielił jeden `SimpleDateFormat` między wątki (klasyczny wyścig: przestawione godziny, wyjątki). Każdy formatter ma teraz własny `ThreadLocal`; parser przyjmuje też `15.30`, `jutro 8:00`, `2026-9-5 9:05`.
- Ochrona TPM liczyła tylko wejście — teraz także wyjście modelu i obrazy, więc limit 65K/min przestał być przekraczany.
- `MediaSessionManager` był pytany o sesje z `null` (SecurityException) — pauza muzyki nigdy nie działała. Teraz podajemy własny komponent nasłuchu powiadomień, a wznawiamy **tylko** to, co sami zatrzymaliśmy.
- `ScreenCaptureService` rejestrował callback MediaProjection **po** `createVirtualDisplay` (na Androidzie 14+ wymagana kolejność odwrotna) i nie łapał błędu startu usługi.
- Tokeny przekazywane do podsumowań: `supabase` DDL/`json`, `SpotifyTools` przestał używać `runBlocking` (blokował wątki puli), `getInt("id")` na wierszach z bazy (liczby vs stringi) już nie rzuca wyjątku, literówki w komunikatach.

**Druga runda (sesja, tryb ręczny, zapis danych):**

- Rozmowa **wznawia się po zerwaniu sieci** i po `goAway` zamiast kończyć się w tym momencie: klient zapamiętuje uchwyt wznowienia (`sessionResumptionUpdate`), odrzuca zdarzenia ze starych gniazd (numer generacji) i ponawia połączenie maks. 3 razy z rosnącym odstępem — dopiero potem zamyka sesję z jasnym powodem. Przy okazji z setupu zniknęło pole `speechConfig.audioConfig` (Live API go nie zna → błąd 400), a limit 429 nie zabija już rozmowy.
- Gesty w trybie ręcznym **meldowały sukces nawet wtedy, gdy system ich nie przyjął** (`dispatchGesture` zwraca wynik, którego nie sprawdzaliśmy). Teraz NIXI wie, kiedy klik się udał, a kiedy gest przerwano — i mówi to uczciwie.
- Współrzędne w `screen_tap`/`screen_swipe`: liczby **0..100 w obu osiach** to procenty ekranu, wszystko inne to piksele (zasada jest w opisie narzędzi). Brak współrzędnych kończy się błędem, a nie kliknięciem w lewy górny róg.
- **Przypomnienia i budziki w końcu zapisują się do bazy**: wysyłaliśmy `created_at` w milisekundach do kolumny `timestamptz`, więc PostgREST odrzucał insert. Datę ustawia teraz baza (`default now()`).
- Po nagraniu nowego szablonu „Hej Nixi” tryb ECO/STANDARD **wracał do domyślnego**, ignorując wybór użytkownika — poprawione.
- `WakeEngine` był przestawiany z wątku UI, a czytany przez wątek audio → dodana synchronizacja (zmiana ustawień w trakcie nasłuchu nie psuje już bufora klatek).
- Zamknięcie okna rozmowy gestem „wstecz” **nie kończyło sesji** (mikrofon zostawał włączony) — teraz okno faktycznie zamykane kończy sesję i sprząta po sobie.
- Kula animuje się na klatkach ekranu (płynniej na 120 Hz, mniej pracy w spoczynku), a powrót do aplikacji odświeża dane z Supabase **najwyżej raz na 15 s** (wcześniej każde wejście na zakładkę to były 3 zapytania).

**Trzecia runda (echo, szybkość startu, trwałość, tło):**

- **Koniec „rozmowy z samą sobą"**: mikrofon zbierał głos NIXI z głośnika i wysyłał go do modelu (fałszywe przerwania, zaśmiecone transkrypcje). Teraz działa **bramka półduplex** (gdy NIXI mówi, nie nadajemy jej własnego głosu) plus **AEC** (`AcousticEchoCanceler`) na sesji mikrofonu. Przerwanie głosem nadal działa — wymaga dwóch głośnych klatek pod rząd (≈128 ms), więc echo i stuknięcia nie ucinają już odpowiedzi.
- **Krótszy start sesji**: prompt systemowy powstawał z ~10 zapytań do Supabase **po kolei** (i po przekroczeniu limitu startował bez kontekstu). Teraz zapytania lecą **równolegle**, persona i profil są **cache'owane lokalnie na 24 h** (z odświeżaniem w tle), a czas budowy promptu trafia do logów (`prompt.build`).
- **TPM liczy też tekst**: wcześniej budżet widział tylko audio, więc prompt, polecenia tekstowe i odpowiedzi narzędzi nie były rozliczane (limit „nigdy nie przekroczymy" był nieprawdą).
- **Nowe fakty i ustawienia w końcu się zapisywały**: `memory_facts` i `admin_table` używały wzorca „PATCH, a jak się nie uda — INSERT". PATCH na nieistniejący klucz zwraca 200 z pustą listą, więc **INSERT nigdy się nie wykonywał** — nowe fakty o Tobie i nowe ustawienia (np. imię asystentki) przepadały. Teraz to prawdziwy upsert (`ON CONFLICT`).
- **Trwała kolejka zapisów**: gdy nie ma internetu, fakty z pamięci, podsumowania rozmów i logi idą do kolejki na dysku i są doręczane, gdy sieć wróci (start aplikacji, koniec sesji, piesek). Bez tego ginęły bez śladu.
- **Piesek nasłuchu**: HyperOS potrafi ubić usługę nasłuchu, gdy aplikacja jest zamknięta — teraz alarm co 15 minut sprawdza, czy „Hej Nixi" nadal żyje, i podnosi nasłuch (plus reakcja na zrzucenie aplikacji z listy ostatnich). Uczciwie: nie pomoże po ręcznym „Wymuś zatrzymanie".
- **Tryb ręczny przeżywa obrót telefonu**: rozmiar podglądu był brany raz przy starcie, więc po obrocie zrzuty były przycięte, a `screen_tap` klikał obok celu. Teraz zmiana wyświetlacza odtwarza podgląd i aktualizuje rozdzielczość dla narzędzi.
- **Auto-ECO**: poniżej 20% baterii (bez ładowania) nasłuch sam przechodzi w tryb ECO — mniej ciepła i zużycia, a „Hej Nixi" nadal działa.
- **Współrzędne w jednym, przetestowanym miejscu**: logika „0..100 w obu osiach = procenty" wyszła z obiektu zależnego od Androida do `util/ScreenCoords` i ma testy jednostkowe; doszły też testy granic dnia (o północy okna dnia stykają się co do sekundy).

**UX na telefonie (Redmi Note 14 Pro 5G):**

- Okno rozmowy dostało **insety** (status bar, wycięcie na aparat, pasek nawigacji) — kula przestała wchodzić pod dziurkę kamery, a przyciski pod pasek gestów.
- Tryb ręczny prosi o zgodę na podgląd ekranu **dokładnie raz** (wcześniej dwa efekty potrafiły wystawić dwa systemowe dialogi), a odmowa jest respektowana i zgłaszana modelowi.
- Nowa sekcja **„Telefon: praca w tle (Xiaomi/HyperOS)”** w Ustawieniach: bateria bez ograniczeń, autostart, edytor uprawnień HyperOS, wezwanie na pełnym ekranie — z podglądem stanu, który odświeża się po powrocie z ustawień systemowych.
- Logi mają większe pola dotyku, ekran główny pokazuje stan sesji, zużycie tokenów i ewentualny problem z mikrofonem.

**Testy:** CI uruchamia testy jednostkowe (`testDebugUnitTest`) dla logiki czasu (formaty, wątki, granice dnia), TPM, DTW wake-worda, odczytu dat przypomnień i współrzędnych trybu ręcznego — oprócz budowania APK.

---

## Budowanie APK (GitHub Actions)

Repozytorium zawiera workflow **`.github/workflows/build-apk.yml`**:

1. Push na `main` (lub branch `arena/**`) → job uruchamia testy jednostkowe i buduje **`debug` i `release` APK**.
2. Pliki pobierzesz w zakładce **Actions → wybrany run → artefakt `NIXI-APK-…`**
   (albo `gh run download <id> -n NIXI-APK-<sha> -D dist`).
   **Do instalacji na telefonie użyj `app-debug.apk`** — jest podpisany kluczem
   debug i instaluje się od razu. `app-release-unsigned.apk` z CI nie jest
   podpisany (to plik „do podpisu” przed publikacją), więc instalator go odrzuci.
3. Tag `v1.0.0` → auto **GitHub Release** z APK w plikach.

Lokalnie (opcjonalnie):

```bash
gradle wrapper            # jednorazowo, jeśli chcesz używać ./gradlew
./gradlew testDebugUnitTest
./gradlew assembleRelease
```

Wymagania: JDK 17, Gradle 8.10+ (workflow sam ustawia oba).

---

## Start (5 minut)

1. **Klucz Gemini** — aistudio.google.com/apikey (darmowy plan; model Live
   musi być dostępny dla Twojego klucza — na free tierze Live ma 65K TPM, 10 RPM, 250 RPD).
2. **Supabase** — utwórz projekt, wklej `supabase/seed.sql` w SQL Editorze,
   skopiuj **URL projektu** i **klucz anon**.
3. Zainstaluj APK, uruchom, przejdź onboarding:
   klucz Gemini → Supabase → **3× „Hej Nixi”** (rejestracja) → uprawnienia → głos.
4. **Ustaw pracę w tle** (sekcja niżej) — to nie jest opcjonalne na Xiaomi.
5. Gotowe: powiedz **„Hej Nixi”** (muzyka się zatrzyma, kula wjedzie w róg ekranu).

### Przydatne zdania na start

- „Hej Nixi, co mam dzisiaj?” (kalendarz + lekcje)
- „Hej Nixi, dodaj przypomnienie o spacerze z psem o 17:30”
- „Hej Nixi, ustaw budzik na 6:45, etykieta wstawa”
- „Hej Nixi, zastępstwo z matematyki” (jeśli masz regułę lub powiedz „oznacz matematykę jako zastępstwo”)
- „Hej Nixi, otwórz tabelę users i pokaż pierwszy wiersz”
- „Hej Nixi, włącz tryb ręczny i otwórz przeglądarkę” (poprosi o zgodę na podgląd)

---

## Praca w tle na Xiaomi (HyperOS)

HyperOS/MIUI zabija aplikacje z mikrofonem w tle wyjątkowo skutecznie. Zrób
**trzy** rzeczy po instalacji (Ustawienia → „Telefon: praca w tle” prowadzi do
każdej z nich jednym tapnięciem):

| # | Co | Gdzie w systemie | Dlaczego |
|---|---|---|---|
| 1 | **Bateria: bez ograniczeń** | Ustawienia → Aplikacje → NIXI → Bateria → „Bez ograniczeń” | Bez tego system usypia aplikację po kilku minutach od zgaszenia ekranu. |
| 2 | **Autostart** | Ustawienia → Aplikacje → NIXI → Autostart (albo Ikona „Bezpieczeństwo” → Autostart) | Bez tego nasłuch nie wstanie po restarcie telefonu. |
| 3 | **Okna w tle / uprawnienia** | Edytor uprawnień HyperOS (przycisk w Ustawieniach NIXI) | Potrzebne, żeby kula rozmowy mogła pokazać się nad innymi aplikacjami i nad blokadą. |

Dodatkowo warto: zablokować NIXI w „Ostatnich aplikacjach” (ikona kłódki),
wyłączyć dla niej „Oszczędzanie baterii w tle” w menedżerze baterii oraz —
jeśli używasz — dopuścić powiadomienia pełnoekranowe (Android 14+: Ustawienia →
Aplikacje → NIXI → „Wezwania na pełnym ekranie”, przycisk w aplikacji).

Sprawdzenie: zgaś ekran, odczekaj 10 minut i powiedz „Hej Nixi”. Jeśli
powiadomienie o nasłuchu zniknęło — cofnij się do tabeli wyżej.

---

## Rozwiązywanie problemów

| Objaw | Najczęstsza przyczyna | Co zrobić |
|---|---|---|
| „Hej Nixi” nie reaguje po zgaszeniu ekranu | ograniczenia baterii HyperOS | punkt 1 tabeli wyżej; sprawdź, czy widzisz powiadomienie „NIXI nasłuchuje” |
| Rozmowa urywa się przy słabym zasięgu | przełączenie Wi-Fi/LTE albo `goAway` z serwera | NIXI wznawia połączenie sama (do 3 prób) i mówi o tym w logach; jeśli powtarza się co chwilę — sprawdź sieć |
| Nasłuch gaśnie po kilku minutach | brak autostartu / force-stop | Autostart + nie „wymuszaj zatrzymania” NIXI |
| Na ekranie głównym „Problem z mikrofonem” | mikrofon zajęty (rozmowa, dyktafon, inna asystentka) | poczekaj — NIXI spróbuje się podłączyć sama; zamknij aplikację trzymającą mikrofon |
| Kula nie wyskakuje po „Hej Nixi” | brak „okien w tle”/pełnoekranowego wezwania | edytor uprawnień HyperOS + punkt 3 tabeli |
| Odpowiedzi przerywane, komunikat o limicie tokenów | darmowy limit 65K TPM | poczekaj ~minutę albo włącz tryb TPM „eco” w Ustawieniach |
| Przypomnienie nie zadzwoniło | alarm wyczyszczony przez system | otwórz aplikację (odtworzy alarmy) i sprawdź, czy NIXI nie jest „wymuszona do zatrzymania” |
| Spotify: „niepołączony” | brak Client ID / redirect | wpisz Client ID, dodaj `nixi://spotify-auth` do Redirect URI w dashboardzie Spotify |

---

## Architektura (skrót)

```
dev.nixi/
├─ NixiApp, NixiState          # stan globalny (kula, sesja, tokeny, pending akcje)
├─ audio/                      # AudioBus (jeden mikrofon, odporny na błędy),
│                               # AudioPlayer (24 kHz, RMS→kula), MediaPauseController
├─ wake/                       # WakeEngine (offline DTW), WakeWordService, EnrollmentController
├─ live/                       # GeminiLiveClient (WS BidiGenerateContent),
│                               # LiveSessionService (sesja + narzędzia + TPM),
│                               # SystemPromptBuilder, TpmGuard, PostSessionMemory
├─ tools/                      # rejestr + implementacje narzędzi (Supabase, Spotify,
│                               # kalendarz, budziki, przypomnienia, powiadomienia, ekran, system)
├─ db/                         # PostgrestClient (cienki REST + OpenAPI discover), SupabaseHub
├─ notif/                      # ActionNotifier, NixiNotificationListener (ciche reguły),
│                               # ReminderScheduler (odtwarzanie alarmów), ReminderReceiver
├─ screen/ + accessibility/    # ScreenCaptureService (MediaProjection),
│                               # NixiAccessibilityService (gesty tap/swipe)
├─ overlay/ConversationActivity# okno z kulą nad blokadką (potwierdzenia, tryb ręczny, SQL)
├─ util/                       # LogBus, TimeUtils (thread-safe), DeviceTweaks (HyperOS)
└─ ui/                         # Compose: onboarding, home, ustawienia, tabele, logi
```

### Ochrona TPM (po stronie klienta)

`TpmGuard` utrzymuje okno 60 s z szacunkiem tokenów (audio wejście i wyjście
25 tok/s, tekst ¼ znaku, obraz px/750). Domyślny limit **55K tok/min**
(bufor pod 65K). Gdy okno się zapełnia: NIXI **przestaje nadawać audio**
(stan `TPM_LIMIT` na kuli), nie zrywa sesji; po 429 od Google — backoff 60 s
i powiadomienie. Tryb `eco` = 45K. Limit da się podnieść/zmniejszyć w
Ustawieniach (albo wyłączyć).

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
| Dostęp do powiadomień | odczyt powiadomień, ciche reguły, **pauza/wznowienie muzyki** |
| Usługa dostępności | tap/scroll w trybie ręcznym (opcjonalne) |
| Dokładne alarmy | przypomnienia o czasie (opcjonalne) |
| MediaProjection (konsent) | zrzuty ekranu w trybie ręcznym (tylko na czas sesji) |
| Wyłączenie optymalizacji baterii | żeby HyperOS nie ubił nasłuchu (systemowy dialog, można odmówić) |

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
- **Spotify**: wymaga Client ID z developer.spotify.com (PKCE,
  bez sekretu w aplikacji).
- **Rutyny**: wstrzykiwane w prompt (trigger → kroki) — działa na rozmowie;
  ciche reguły powiadomień działają niezależnie.
- **Pauza muzyki** działa tylko, gdy NIXI ma dostęp do powiadomień (to warunek
  systemowy na listę aktywnych sesji multimediów).
- **Kula nad blokadą** wymaga zgody systemu na wezwanie na pełnym ekranie —
  bez niej dostaniesz heads-up zamiast okna.

## Bezpieczeństwo

- Klucze (Gemini, Supabase anon, Spotify token) trzymasz **lokalnie**
  w prywatnych SharedPreferences urządzenia.
- RLS w seed.sql jest permissive, bo to osobisty projekt — nie udostępniaj
  klucza anon publicznie.
- Tryb ręczny uruchamia się **tylko** z Twojej zgody (systemowy konsent)
  i tylko na czas sesji; zrzuty ekranu nie są zapisywane na dysku, lecą
  bezpośrednio do modelu i znikają z pamięci po wysłaniu.
