# Nixi na Windows — plik .exe

## Co dostajesz

`Nixi.exe` — pojedynczy, przenośny plik (PyInstaller, onefile). Nie wymaga Pythona ani żadnej instalacji.
Po uruchomieniu:

1. ekran pokazuje interfejs Nixi **zamiast tapety** (pełny ekran, pod wszystkimi oknami),
2. Nixi **nasłuchuje w tle** słowa „Hej Nixi" (lokalnie, offline),
3. pojawia się ikona w zasobniku systemowym (menu: pokaż pulpit, włącz/wyłącz nasłuch, ustawienia, zakończ).

## Pierwsze uruchomienie

- **SmartScreen** może zapytać o zgodę (plik nie ma podpisu cyfrowego) → *Więcej informacji → Uruchom mimo to*.
- Nixi pobierze model mowy VOSK (`vosk-model-small-pl-0.22`, ok. 40 MB) z
  `alphacephei.com` (mirror: GitHub). Bez internetu wybudzanie głosowe nie zadziała
  do czasu pobrania — wtedy użyj skrótu `Ctrl+Shift+Alt+N`.
- Wklej **klucz Gemini API** w ustawieniach (⚙) i kliknij *Testuj klucz*.
  Format klucza: `AIza…` z [aistudio.google.com/apikey](https://aistudio.google.com/apikey).
- Bez klucza aplikacja działa w trybie demo (pokazuje interfejs i przepływ).

## Dane aplikacji

- `%LOCALAPPDATA%\Nixi\settings.json` — ustawienia,
- `%LOCALAPPDATA%\Nixi\nixi.db` — pamięć długotrwała (SQLite),
- `%LOCALAPPDATA%\Nixi\models\` — model mowy VOSK (i miejsce na własny model wake worda),
- `%LOCALAPPDATA%\Nixi\logs\nixi.log` — logi (przy zgłaszaniu problemów).

## Autostart z Windowsem

Ustawienia → *Uruchamiaj Nixi razem z systemem* → Zapisz (wpis w
`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`).

## Własny model wake word (opcjonalnie, zaawansowane)

Domyślnie „Hej Nixi" wykrywane jest przez VOSK (frazy-regex w ustawieniach).
Jeśli masz własny model openWakeWord (ONNX, np. wytrenowany w openwakeword.com),
wrzuć `hey_nixi.onnx` do `%LOCALAPPDATA%\Nixi\models\` — zostanie użyty
automatycznie (wymaga też `melspectrogram.onnx` i `embedding_model.onnx` obok;
pobierane są przy pierwszym użyciu, jeśli brak). Detekcja OWW działa równolegle z VOSK.

## Budowa .exe

Budowa jest w pełni zautomatyzowana przez GitHub Actions (`.github/workflows/build-windows.yml`):

1. runner Windows + Python 3.12,
2. instalacja zależności (`requirements.txt` + `requirements-build.txt`),
3. **samotest z testem akustycznym**: synteza „Hej Nixi" (Piper, polski głos) → VOSK → asercja
   wykrycia + test negatywny (brak fałszywych wybudzeń). Build pada, jeśli wake word nie działa,
4. `pyinstaller Nixi.spec` → `dist/Nixi.exe` (onefile, windowed, ikona, metadane wersji),
5. artefakt `Nixi-windows-exe`.

Lokalnie (Windows z Pythonem 3.11+):

```powershell
pip install -r requirements.txt -r requirements-build.txt
python scripts/gen_icon.py
pyinstaller --noconfirm --clean Nixi.spec
```

## Diagnostyka

```powershell
Nixi.exe --version
Nixi.exe --reset-settings      # reset ustawień
Nixi.exe --windowed            # okno deweloperskie (nie pełny ekran)
Nixi.exe --verbose             # szczegółowe logi
```

Logi: `%LOCALAPPDATA%\Nixi\logs\nixi.log`.

## Znane ograniczenia

- **Antywirusy** czasem flagują niespodziewane PyInstaller-onefile (fałszywe alarmy). Dodaj wyjątek lub użyj trybu deweloperskiego.
- Sesja Live API trwa maks. ok. 10 min — Nixi sama ponawia połączenie.
- Aplikacje w trybie wyłączności (gry fullscreen) mogą blokować globalne skróty i zrzuty ekranu.
- Sterowanie myszą/klawiaturą może kolidować z aplikacjami wymagającymi uprawnień administratora (Nixi działa z uprawnieniami użytkownika; uruchomienie jako administrator rozwiązuje to kosztem bezpieczeństwa).
