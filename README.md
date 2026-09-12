# Nixi ✦ — asystentka głosowa Twojego komputera

**Nixi** to osobista asystentka, która mieszka na Twoim pulpicie, słyszy Cię w tle i naprawdę **widzi oraz steruje Twoim ekranem i laptopem** — na żywo, głosem. Działa na modelu **Gemini Live API** (`gemini-3.1-flash-live-preview`) z natywnym audio–audio, wizją i tool callingiem.

> ⚠️ Uwaga: model `gemini-3.1-live-preview` o takiej nazwie nie istnieje — właściwy identyfikator modelu Live API to **`gemini-3.1-flash-live-preview`** (jedyny „live" model Gemini 3.1) i dokładnie on jest tu używany (można go zmienić w ustawieniach).

---

## Co potrafi Nixi

| Obszar | Umiejętności |
|---|---|
| 🎙️ **Rozmowa** | naturalna rozmowa głosowa (audio↔audio przez WebSocket), niska latencja, przerywanie (barge-in), transkrypcja na żywo |
| 🖥️ **Ekran** | widzi Twój ekran (zrzuty JPEG wysyłane w trakcie rozmowy), rozpoznaje co jest otwarte |
| 🖱️ **Sterowanie** | otwiera/zamyka aplikacje, klika, przesuwa mysz, przewija, pisze tekst, wciska skróty klawiszowe, zmienia głośność i jasność, blokuje/usypia/zamyka system |
| 🧠 **Pamięć długotrwała** | zapamiętuje fakty o Tobie (imię, preferencje, plany), notatki, historia rozmów — wszystko w lokalnej bazie SQLite |
| 🌐 **Internet** | wyszukiwanie w Google, otwieranie stron |

## Najważniejsze zasady bezpieczeństwa

1. **WebSocket otwiera się TYLKO po słowie „Hej Nixi"** — w stanie czuwania nic nie jest wysyłane do sieci, a Nixi nie słyszy Twoich rozmów (nasłuch wake worda działa w 100% lokalnie, offline).
2. **Po pożegnaniu** („do widzenia", „na razie", „przestań słuchać", „koniec"…) Nixi sama rozłącza WebSocket i wraca do czuwania. Cisza również kończy sesję (domyślnie 45 s).
3. **Ryzykowne akcje wymagają zgody**: kliknięcia, pisanie tekstu, skróty klawiszowe, zamykanie aplikacji, blokada/uśpienie/zamknięcie systemu — Nixi najpierw zapyta *„Czy mogę to zrobić?"* i wykona dopiero po Twoim „tak". Można zaostrzyć do „potwierdzaj wszystko" w ustawieniach.
4. **Pojedynczy klik na interfejs nic nie robi** (ochrona przed przypadkowymi akcjami). Podwójny klik na kulę włącza/wyłącza nasłuch.
5. Nieodwracalne akcje (np. shutdown) mają dodatkowo opóźnienie i można je anulować.

## Jak to działa (architektura)

```
Mikrofon ──► VAD ──► [IDLE] detektor „Hej Nixi” (VOSK, polski, offline, lokalnie)
                        │  wake word!
                        ▼
              WAKING: otwarcie WebSocket ──► Gemini Live API (gemini-3.1-flash-live-preview)
                        │  audio (16 kHz PCM) + zrzuty ekranu (JPEG) ──► model
                        │  ◄── audio odpowiedzi (PCM 24 kHz) + transkrypcje + tool calls
                        ▼
              Narzędzia (wykonywane lokalnie) ──► ekran, aplikacje, system, pamięć
                        │
              Pożegnanie / cisza ──► rozłączenie WS ──► powrót do IDLE
```

- **UI**: Qt6/QML — pełnoekranowa „tapeta" (pod wszystkimi oknami) z animowaną kulą (shader), pigułką statusu, transkrypcją, banerami zgód i toastami pamięci. W tacy systemowej menu + skróty.
- **Maszyna stanów** gwarantuje, że żadna akcja nie wykona się w złym stanie (np. w czuwaniu).
- **Pamięć**: SQLite + FTS5 (pełnotekstowo po polsku, z foldowaniem znaków), opcjonalnie semantycznie przez Gemini Embeddings (`text-embedding-004`).

## Wymagania

- Windows 10/11 (wersja **.exe**) — także działa ze źródeł na Linux/macOS (bez sterowania ekranem),
- mikrofon i głośniki,
- internet do rozmów z Gemini,
- klucz Gemini API (darmowy z [aistudio.google.com/apikey](https://aistudio.google.com/apikey)) — bez klucza działa tryb demo.

## Instalacja (Windows)

1. Zbuduj **`Nixi.exe`** samodzielnie: `powershell -ExecutionPolicy Bypass -File scripts\build_windows.ps1` (lub skopiuj `docs/build-windows.workflow.yml` do `.github/workflows/build-windows.yml` i uruchom GitHub Actions — artefakt `Nixi-windows-exe`).
2. Uruchom. (SmartScreen: „Więcej informacji" → „Uruchom mimo to" — plik nie jest podpisany certyfikatem.)
3. Przy pierwszym uruchomieniu Nixi pobierze model rozpoznawania mowy (ok. 40 MB, tylko raz) — potrzebny do słowa „Hej Nixi".
4. Kliknij **⚙** (prawy dolny róg) → wklej **klucz API** → „Testuj klucz" → „Zapisz".
5. Powiedz **„Hej Nixi"** 😊

Opcjonalnie: w ustawieniach zaznacz **„Uruchamiaj Nixi razem z systemem"** — Nixi będzie startować z Windowsem jako tapeta i czuwać w tle.

> Nixi zapisuje ustawienia i pamięć w `%LOCALAPPDATA%\Nixi` (klucz API jest przechowywany tylko tam, lokalnie na Twoim komputerze).

## Sterowanie głosowe — przykłady

- „Hej Nixi, otwórz notatnik"
- „Hej Nixi, co widzisz na ekranie?"
- „Hej Nixi, kliknij przycisk Zapisz" (poprosi o zgodę → powiedz „tak")
- „Hej Nixi, wpisz w dokument: Drogi Panie…"
- „Hej Nixi, zrób głośniej / wycisz dźwięk"
- „Hej Nixi, zapamiętaj, że moja córka ma na imię Lena"
- „Hej Nixi, co o mnie pamiętasz?"
- „Hej Nixi, znajdź w internecie pogodę w Krakowie"
- „Hej Nixi, do widzenia" — rozłącza się i wraca do czuwania

## Skróty klawiszowe (globalne)

| Skrót | Działanie |
|---|---|
| `Ctrl+Shift+Alt+N` | włącz / zakończ nasłuch |
| `Ctrl+Shift+Alt+Esc` | natychmiast zakończ sesję |
| `Ctrl+Shift+Alt+Q` | wyjdź z Nixi |
| `Esc` (podczas aktywnej sesji) | zakończ nasłuch |
| podwójny klik na kulę | włącz / wyłącz |

## Uruchomienie w VS Code (Windows)

Nixi można najpierw uruchomić ze źródeł — do testów nie potrzebujesz jeszcze pliku `.exe`.
Potrzebujesz 64-bitowego **Pythona 3.11 lub 3.12**, Gita, VS Code z rozszerzeniem
**Python** oraz mikrofonu. Plik `.exe` buduj później na Windows, najlepiej na tej samej
architekturze, na której będzie używany.

```powershell
# 1. Klonowanie
git clone https://github.com/rejson59/Nixi.git
cd Nixi
code .

# 2. Środowisko w terminalu VS Code (PowerShell)
py -3.12 -m venv .venv
Set-ExecutionPolicy -Scope Process Bypass
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python -m pip install -r requirements-build.txt

# 3. Uruchomienie — bez klucza API włączy się bezpieczny tryb demo
python -m nixi --windowed --verbose
```

W VS Code wybierz `Python: Select Interpreter` i wskaż
`.venv\Scripts\python.exe`. W repozytorium są gotowe konfiguracje w `.vscode`:
`Nixi: tryb okienkowy` oraz `Nixi: samotesty`. Alternatywnie można uruchomić
`python -m nixi.selftest` z terminala.

Klucz Gemini możesz wkleić w ustawieniach aplikacji albo ustawić tylko na czas bieżącego
terminala:

```powershell
$env:GEMINI_API_KEY = "AIza..."
python -m nixi --windowed --verbose
```

Bez klucza przetestujesz interfejs i przepływ demo. Po dodaniu klucza potrzebny jest internet;
przy pierwszym uruchomieniu pobierany jest lokalny model VOSK do słowa „Hej Nixi”. Jeśli
mikrofon/model nie jest jeszcze gotowy, użyj skrótu `Ctrl+Shift+Alt+N`.

### Testy i budowa `.exe`

```powershell
# samotesty: pamięć, VAD, stany, narzędzia i mock Live API
python -m nixi.selftest

# opcjonalny, dłuższy test akustyczny Piper → VOSK
python -m pip install -r requirements-acoustics.txt
python -m nixi.selftest --acoustics

# opcjonalny zrzut testowego interfejsu
python -m nixi.selftest --screenshot tests_output\ui.png

# lokalna budowa (generuje dist\Nixi.exe)
powershell -ExecutionPolicy Bypass -File scripts\build_windows.ps1

# wariant z opcjonalnym testem akustycznym
powershell -ExecutionPolicy Bypass -File scripts\build_windows.ps1 -Acoustics
```

Jeśli testujesz na Linuxie bez bibliotek OpenGL, test QML może zostać oznaczony jako pominięty;
na Windows uruchomi się normalnie. Sam `Nixi.exe` musi być budowany na Windows przez PyInstaller.

Klucz API nie trafia do repozytorium: aplikacja zapisuje go lokalnie w
`%LOCALAPPDATA%\Nixi\settings.local.json`, a pozostałe ustawienia w `settings.json`.

## Dla deweloperów

```bash
# Linux/macOS — środowisko
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt -r requirements-build.txt

# uruchomienie (okno deweloperskie)
python -m nixi --windowed --verbose
```

Struktura:

```
nixi/
├── audio/       mikrofon, VAD, synteza dźwięków, odtwarzacz, detektor „Hej Nixi” (VOSK)
├── core/        klient Live API (WebSocket), sesja (zgody, pożegnania, wizja), pamięć, demo
├── tools/       tool calling: ekran, aplikacje, głośność, system, notatki, pamięć…
├── ui/          QML (tapeta z kulą + ustawienia) i mostek Qt (controller)
├── config.py    ustawienia (settings.json w katalogu danych)
├── state.py     maszyna stanów (czuwanie→słuchanie→…→czuwanie)
└── selftest.py  samotesty
```

### Słowo wybudzające „Hej Nixi"

Detekcja działa lokalnie przez **VOSK** (model `vosk-model-small-pl-0.22`, automatycznie pobierany; mirror zapasowy na GitHubie). Domyślne frazy to wyrażenia regularne (`hej nixi`, `hej niki`, `hej nicki`…), które możesz dowolnie zmienić w ustawieniach (jedna na linię). Możesz też wrzucić własny model ONNX openWakeWord do katalogu danych (`%LOCALAPPDATA%\Nixi\models\hey_nixi.onnx`) — patrz `docs/WINDOWS_EXE.md`.

### Klucz API

Kolejność odczytu: `settings.local.json` (katalog danych) → zmienna `GEMINI_API_KEY` → `settings.json`. Klucz możesz też wkleić w ustawieniach aplikacji.

## Ograniczenia

- Model Live API to wersja *preview* — funkcja tool calling jest synchroniczna, a połączenie trwa maks. ok. 10 minut (Nixi sama się wtedy przełącza).
- Sterowanie ekranem (mysz/klawiatura/aplikacje/głośność/jasność/zasilanie) działa na Windows.
- Zrzuty ekranu z monitora głównego oraz wszystkich monitorów (składane obok siebie); aplikacje na wyłączności (np. gry fullscreen) mogą nie być widoczne na zrzutach.
- Na maszynach wirtualnych/zdalnych pulpitach efekty shadera można wyłączyć w ustawieniach.

## Prywatność

- W czuwaniu **nic nie jest wysyłane do sieci** — mikrofon analizowany jest wyłącznie lokalnie (VOSK/VAD).
- Audio i zrzuty ekranu trafiają do Google **tylko podczas aktywnej sesji** (po „Hej Nixi" i do pożegnania/ciszy).
- Pamięć, notatki, historia rozmów i ustawienia są przechowywane wyłącznie lokalnie (`%LOCALAPPDATA%\Nixi`).
- Hasła nigdy nie są zapamiętywane (zakaz w prompcie systemowym), a wpisywanie tekstu wymaga potwierdzenia.
