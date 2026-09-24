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
| **Wake-word** | „Hej Nixi” — **offline**, bez modeli i sieci: bramka mowy + frontend log-mel + dwustopniowa kaskada (tani pierwszy stopień, dokładny drugi) + filtr podpisu mówcy. Próg kalibrowany z Twoich 3 nagrań, detektor uczy się fałszywych trafień. Niskie zużycie: w ciszy samo liczenie energii, jeden wspólny `AudioRecord`, brak wakelocka, tryb ECO. Działa w tle i **nad ekranem blokady**. |
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

**Czwarta runda (wake-word w stylu hotwordu Google):**

- **„Hej Nixi" działało dopiero po chwili od włączenia nasłuchu**: kwarantanna po trafieniu (12 s) startowała od zera czasu audio, więc po starcie usługi każda ocena kończyła się na „jeszcze w kwarantannie" — na telefonie wyglądało to jak „muszę chwilę poczekać, zanim zadziała", a po każdym restarcie nasłuchu (np. przez piesek) znowu było 12 s ciszy. Znalazł to test kaskady, a symulacja 1:1 potwierdziła objaw.
- **Detektor od nowa (kaskada)**: zamiast jednego Goertzla z DTW — bufor 2 s PCM, bramka mowy (w ciszy zero FFT), frontend log-mel 25 ms, tani pierwszy stopień na 8 pasmach, dokładny drugi stopień na 16 pasmach i 3 nagraniach, filtr podpisu mówcy i lista negatywów uczona z niepotwierdzonych kandydatów. Szczegóły niżej.
- **Pre-roll z bufora**: pierwsza sylaba frazy nie wypada, bo klatki sprzed otwarcia bramki liczymy z bufora 2 s (wcześniej początek wypowiedzi mógł zostać obcięty).
- **Uczciwa statystyka**: „ms CPU / min nasłuchu" w Ustawieniach było zawsze zerem, bo nikt tej liczby nie liczył. Teraz jest mierzona, a detektor loguje raz na minutę pełne statystyki (`wake.stats`) — łącznie z tym, ile klatek już zebrał i jakie ma progi, żeby dało się odróżnić zły próg od zbyt krótkiej historii.
- **Nowy szablon wymaga jednorazowej rejestracji**: stary format (cechy Goertzla) jest niekompatybilny — aplikacja wykrywa to i pokazuje prośbę o nagranie „Hej Nixi" od nowa, zamiast udawać, że nasłuchuje.

**Trzecia runda (echo, szybkość startu, trwałość, tło):**

- **Koniec „rozmowy z samą sobą"**: mikrofon zbierał głos NIXI z głośnika i wysyłał go do modelu (fałszywe przerwania, zaśmiecone transkrypcje). Teraz działa **bramka półduplex** (gdy NIXI mówi, nie nadajemy jej własnego głosu) plus **AEC** (`AcousticEchoCanceler`) na sesji mikrofonu. Przerwanie głosem nadal działa — wymaga dwóch głośnych klatek pod rząd (≈128 ms), więc echo i stuknięcia nie ucinają już odpowiedzi.
- **Krótszy start sesji**: prompt systemowy powstawał z ~10 zapytań do Supabase **po kolei** (i po przekroczeniu limitu startował bez kontekstu). Teraz zapytania lecą **równolegle**, persona i profil są **cache'owane lokalnie na 24 h** (z odświeżaniem w tle), a czas budowy promptu trafia do logów (`prompt.build`).
- **TPM liczy też tekst**: wcześniej budżet widział tylko audio, więc prompt, polecenia tekstowe i odpowiedzi narzędzi nie były rozliczane (limit „nigdy nie przekroczymy" był nieprawdą).
- **Nowe fakty i ustawienia w końcu się zapisywały**: `memory_facts` i `admin_table` używały wzorca „PATCH, a jak się nie uda — INSERT". PATCH na nieistniejący klucz zwraca 200 z pustą listą, więc **INSERT nigdy się nie wykonywał** — nowe fakty o Tobie i nowe ustawienia (np. imię asystentki) przepadały. Teraz to prawdziwy upsert (`ON CONFLICT`).
- **Trwała kolejka zapisów**: gdy nie ma internetu, fakty z pamięci, podsumowania rozmów i logi idą do kolejki na dysku i są doręczane, gdy sieć wróci (start aplikacji, koniec sesji, piesek). Bez tego ginęły bez śladu.
- **Piesek nasłuchu**: HyperOS potrafi ubić usługę nasłuchu, gdy aplikacja jest zamknięta — teraz alarm co 15 minut sprawdza, czy „Hej Nixi" nadal żyje, i podnosi nasłuch (plus reakcja na zrzucenie aplikacji z listy ostatnich). Uczciwie: nie pomoże po ręcznym „Wymuś zatrzymanie".
- **Tryb ręczny przeżywa obrót telefonu**: rozmiar podglądu był brany raz przy starcie, więc po obrocie zrzuty były przycięte, a `screen_tap` klikał obok celu. Teraz zmiana wyświetlacza odtwarza podgląd i aktualizuje rozdzielczość dla narzędzi.
- **Auto-ECO**: poniżej 20% baterii (bez ładowania) nasłuch sam przechodzi w tryb ECO — mniej ciepła i zużycia, a „Hej Nixi" nadal działa.
- **Okno rozmowy nie przyciemnia już ekranu**: tło (Twoja aplikacja, film, cokolwiek masz pod spodem) zostaje widoczne. Kula, stan NIXI i licznik tokenów są teraz w jednej **szklanej pigułce na górze, na środku** (w piątej rundzie doszły do niej także przyciski — pasek na dole zniknął; szczegóły niżej).
- **Współrzędne w jednym, przetestowanym miejscu**: logika „0..100 w obu osiach = procenty" wyszła z obiektu zależnego od Androida do `util/ScreenCoords` i ma testy jednostkowe; doszły też testy granic dnia (o północy okna dnia stykają się co do sekundy).

**Piąta runda (wygląd: jedna pigułka, posegregowane ustawienia):**

- **Całe wywołanie NIXI w jednej szklanej pigułce** na górze ekranu: kula, stan („słucham…”, „myślę…”), ostatnie narzędzie, licznik tokenów i sterowanie (ręczny / aplikacja / koniec). Dolny pasek zniknął, więc nic nie zasłania treści pod spodem.
- **Pigułka wjeżdża z góry ekranu** (`slideInVertically` + rozjaśnienie, kula delikatnie „wskakuje”), a przy zamykaniu chowa się tą samą drogą, zanim okno zniknie — koniec ze skokowym pojawianiem się okna.
- **Zero przyciemniania — teraz z twardą gwarancją**: oprócz `backgroundDimEnabled=false` okno czyści `FLAG_DIM_BEHIND` i ustawia `setDimAmount(0)`, a paski systemowe w tym oknie są przezroczyste (wcześniej zostawał po nich ciemny pas — to właśnie wyglądało jak „przyciemnione tło”).
- **Ustawienia posegregowane w cztery zakładki**: Mózg (Gemini, limity tokenów, głos), Nasłuch (czułość, ECO, rejestracja wzorca, statystyki), Dane (Supabase, Spotify, prywatność), Telefon (praca w tle na HyperOS, uprawnienia). Każda grupa to szklana karta z opisem, a nie ciąg luźnych wierszy.
- **Jeden wspólny język wyglądu** (`ui/components/Glass.kt`): karty, pastylki, przyciski i zakładki w jednym miejscu — ekran główny, nawigacja, ustawienia, onboarding, **Logi, Tabele i szczegóły tabeli** wyglądają spójnie, a tło ma łagodny gradient zamiast płaskiej czerni.
- **Logi i Tabele dostały ten sam szlif**: filtry jako pastylki, wiersze logów jako szklane karty (błędy z czerwonym tintem zamiast jednolitej czerwonej płachty), karty tabel ze znaczkami dostępu NIXI (czyta/edytuje/usuwa) i panel propozycji SQL z podpowiedzią, gdzie go wkleić.
- **Makieta do obejrzenia** (pigułka, ustawienia, ekran główny, tabele, logi): `docs/podglad_ui.html` — otwórz w przeglądarce, żeby zobaczyć docelowy wygląd pigułki, ustawień i ekranu głównego bez instalowania APK (to rysunek w HTML-u, nie zrzut z telefonu; kolory i przezroczystości są te same co w kodzie).

**UX na telefonie (Redmi Note 14 Pro 5G):**

- Okno rozmowy dostało **insety** (status bar, wycięcie na aparat, pasek nawigacji) — pigułka nie wchodzi pod dziurkę kamery ani pod pasek gestów.
- Tryb ręczny prosi o zgodę na podgląd ekranu **dokładnie raz** (wcześniej dwa efekty potrafiły wystawić dwa systemowe dialogi), a odmowa jest respektowana i zgłaszana modelowi.
- Nowa sekcja **„Telefon: praca w tle (Xiaomi/HyperOS)”** w Ustawieniach: bateria bez ograniczeń, autostart, edytor uprawnień HyperOS, wezwanie na pełnym ekranie — z podglądem stanu, który odświeża się po powrocie z ustawień systemowych.
- Logi mają większe pola dotyku, ekran główny pokazuje stan sesji, zużycie tokenów i ewentualny problem z mikrofonem.

**Testy:** CI uruchamia testy jednostkowe (`testDebugUnitTest`) dla logiki czasu (formaty, wątki, granice dnia), TPM, frontendu mel (FFT/mel/podpis), bufora i bramki mowy, całej kaskady na syntetycznej frazie (rozpoznaje swoją frazę, nie reaguje na inne słowa, ciszę i szum), odczytu dat przypomnień i współrzędnych trybu ręcznego — oprócz budowania APK.

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
2. **Supabase** — utwórz projekt i skopiuj **URL projektu** oraz **klucz anon**.
   Tabele zakładają się **same**: aplikacja tworzy brakujące i dokłada nowe
   kolumny przy każdym starcie (oraz przyciskiem w Ustawieniach → Dane).
   Jeśli chcesz pełnej automatyzacji od pierwszej chwili, dodaj jeszcze
   **token osobisty** (sbp_…, supabase.com/dashboard/account/tokens).
   Bez tokenu wystarczy raz wkleić `supabase/seed.sql` w SQL Editorze —
   od tego momentu NIXI wykonuje ten sam SQL sama, przez funkcję
   `nixi_exec_sql`, którą tamten plik tworzy.
3. Zainstaluj APK, uruchom, przejdź onboarding:
   klucz Gemini → Supabase → **3× „Hej Nixi”** (rejestracja) → uprawnienia → głos.
4. **Ustaw pracę w tle** (sekcja niżej) — to nie jest opcjonalne na Xiaomi.
5. Gotowe: powiedz **„Hej Nixi”** (muzyka się zatrzyma, kula wjedzie w róg ekranu).

### Gdy NIXI milczy — jedno tapnięcie

Na ekranie głównym jest karta **„Rozmowa z Gemini”** z przyciskiem
**„Sprawdź NIXI”**. Samokontrola sprawdza po kolei: klucz API Gemini
(zapytuje listę modeli, więc wykryje też zły klucz i model bez trybu Live),
nazwę modelu, mikrofon, nasłuch „Hej Nixi”, połączenie z Supabase i strukturę
tabel, usługę dostępności oraz dostęp do powiadomień — i przy każdym punkcie
podaje, co zrobić, jeśli coś nie działa. Powód nieudanej rozmowy (dokładny
komunikat z serwera Gemini) pokazuje się też w pigułce rozmowy.

NIXI sama dopasowuje format konfiguracji sesji Live (dokumentacja Google ma
tu dwa różne warianty — nie ma sensu, żebyś zgadywał, który jest właściwy):
próbuje po kolei, a działający zapamiętuje.

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
├─ wake/                       # MelFrontend, VadGate, PcmRing, WakeEngine (kaskada), WakeWordService, EnrollmentController
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

### Wake-word — dwustopniowa kaskada jak w hotwordzie Google

Detektor odwzorowuje publicznie opisaną architekturę hotwordu Google
(„A Cascade Architecture for Keyword Spotting on Mobile Devices”, NIPS 2017):
zawsze włączony **tani pierwszy stopień** decyduje, czy w ogóle warto liczyć
cokolwiek droższego, a **drugi stopień** (plus filtr mówcy) rozstrzyga ostatecznie.

1. **Bufor 2 s PCM 16 kHz** (`PcmRing`, 64 kB — tyle samo, ile Google rezerwuje
   na DSP) i **bramka mowy** na energii ramki. W ciszy nie liczymy nic poza
   energią: brak FFT, brak alokacji, brak DTW. Bramka ma histerezę (9 dB
   otwarcie / 4 dB zamknięcie, 300 ms wygaszenia) i uczy się podłogi szumu
   otoczenia, więc działa tak samo w cichym pokoju i w autobusie.
2. **Frontend log-mel**: preemfaza 0,97 → okno Hamminga 25 ms → FFT 512 →
   16 trójkątnych filtrów mel (40–7600 Hz) → logarytm energii. Zgodnie z pracą:
   „the log of the triangular mel filters applied to the power spectra”.
3. **Nadrabianie z bufora (pre-roll)**: gdy bramka się otworzy, klatki sprzed
   otwarcia liczymy **wstecz z bufora 2 s**, więc pierwsza sylaba nigdy nie
   wypada — dokładnie po to Google trzyma bufor „enough audio to safely fit
   the keyword”.
4. **Pierwszy stopień** (ma być czuły, nie musi być precyzyjny): DTW na
   8 kanałach (pasma złożone parami) co 30–60 ms, jeden szablon referencyjny,
   luźny próg. Uruchamia się **tylko na klatkach mowy**.
5. **Drugi stopień** (dopiero po kandydacie): DTW na pełnych 16 kanałach,
   przeciwko **wszystkim 3 nagraniom**, z automatycznym doborem długości okna
   (0,8 / 1,0 / 1,2 × szablon — bo tempo mowy bywa różne) i normalizacją CMVN.
6. **Trzeci filtr — podpis mówcy**: z 3 nagrań liczymy długoterminowy profil
   widma (LTAS) i próg podobieństwa kosinusowego; kandydat o innym profilu
   odpada. Google: „reduce the overall FAR by a factor of 5 to 10 while adding
   less than 1% absolute additional FRR”. Dodatkowo detektor **uczy się**
   fałszywych trafień: kandydat, który przeszedł drugi stopień, ale nie doczekał
   się potwierdzenia, zapisuje swój podpis na listę negatywów.
7. **Potwierdzenie i kwarantanna**: potrzebne są dwa dobre trafienia drugiego
   stopnia w odstępie ≥100 ms (odpowiednik wygładzania posteriorów po oknie
   w dekoderze Google), potem 12 s cooldownu, pauza multimediów → sesja Live →
   kula w rogu.

Progi nie są liczbami z sufitu: rozrzut między **Twoimi własnymi** próbami
(rejestracja 3×) ustala skalę — im stabilniej mówisz, tym ostrzejszy próg.
Rozrzut jest ograniczony z góry (żeby trzy bardzo różne nagrania nie zrobiły
z detektora przepuszczalnego sitka) i z dołu (żeby identyczne nagrania nie
dały progu zerowego).

W ustawieniach: czułość 0–100%, tryb ECO (hop 20 ms = o połowę mniej FFT
i ocen drugiego stopnia) oraz statystyki detektora w logach
(`wake.stats`: ramki ciszy/mowy, kandydaci, ile odrzucił drugi stopień, podpis,
negatywy, trafienia).

**Po aktualizacji z v1.1.0 trzeba nagrać „Hej Nixi” ponownie** — stary szablon
był zbudowany na innych cechach (Goertzel) niż nowy frontend mel. Aplikacja to
wykryje, pokaże prośbę o rejestrację i nie będzie udawać, że nasłuchuje.

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
- **DDL** (nowe tabele/kolumny): klucz anon Supabase nie może zmieniać
  struktury bazy (to zabezpieczenie Supabase, nie usterka NIXI). Dlatego
  aplikacja zakłada i aktualizuje tabele trzema drogami: **token osobisty**
  (sbp_…) → **funkcja `nixi_exec_sql`** w bazie → **jednorazowe wklejenie
  `supabase/seed.sql`** (przycisk kopiuje SQL do schowka i otwiera SQL
  Editor). Wiersze: pełny CRUD.
- **Spotify**: wymaga Client ID z developer.spotify.com (PKCE,
  bez sekretu w aplikacji).
- **Rutyny**: wstrzykiwane w prompt (trigger → kroki) — działa na rozmowie;
  ciche reguły powiadomień działają niezależnie.
- **Pauza muzyki** działa tylko, gdy NIXI ma dostęp do powiadomień (to warunek
  systemowy na listę aktywnych sesji multimediów).
- **Kula nad blokadą** wymaga zgody systemu na wezwanie na pełnym ekranie —
  bez niej dostaniesz heads-up zamiast okna.

## Historia zmian (skrót)

- **1.3.2** — naprawa „NIXI nie odpowiada”: automatyczne dopasowanie formatu
  konfiguracji sesji Live + dokładny komunikat błędu z serwera (w pigułce
  i na ekranie głównym); samokontrola „Sprawdź NIXI”; samoczynne zakładanie
  i aktualizowanie tabel Supabase (token osobisty / `nixi_exec_sql` /
  jednorazowe wklejenie SQL); trzystanowy wskaźnik usługi dostępności
  (działa / włączona, czeka / wyłączona) zamiast fałszywego „nie działa”.
- **1.3.1** — szkło na ekranach Logi, Tabele i szczegółach tabeli.
- **1.3.0** — jedna szklana pigułka NIXI ze slide-in, posegregowane
  ustawienia, szklany styl wszystkich ekranów.
- **1.2.0** — przebudowany detektor „Hej Nixi” (kaskada mel + DTW),
  wymaga ponownego nagrania frazy.

## Bezpieczeństwo

- Klucze (Gemini, Supabase anon, token osobisty, Spotify token) trzymasz
  **lokalnie** w prywatnych SharedPreferences urządzenia — nie idą nigdzie
  poza Twój projekt (token osobisty tylko do api.supabase.com).
- RLS w seed.sql jest permissive, bo to osobisty projekt — nie udostępniaj
  klucza anon publicznie.
- Tryb ręczny uruchamia się **tylko** z Twojej zgody (systemowy konsent)
  i tylko na czas sesji; zrzuty ekranu nie są zapisywane na dysku, lecą
  bezpośrednio do modelu i znikają z pamięci po wysłaniu.
