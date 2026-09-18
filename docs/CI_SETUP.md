# Aktualizacja workflow CI — instrukcja ręczna

Agent nie mógł wypchnąć zmian w `.github/workflows/` (token GitHub App nie ma
uprawnienia `workflows`). Cała reszta zmian jest już w gałęzi.

Gotowa treść nowego workflow leży w **`docs/build-windows.workflow.yml`**.

## Jak wgrać (najprościej — przez stronę GitHuba)

1. Wejdź na swoją gałąź `arena/01a0b051-nixi` na GitHubie.
2. Otwórz plik `.github/workflows/build-windows.yml` → ikona ołówka (*Edit*).
3. Zaznacz całą zawartość i wklej treść z `docs/build-windows.workflow.yml`.
4. *Commit changes* → commit bezpośrednio na gałąź `arena/01a0b051-nixi`.

## Albo lokalnie (git)

```bash
git checkout arena/01a0b051-nixi
git pull
cp docs/build-windows.workflow.yml .github/workflows/build-windows.yml
git add .github/workflows/build-windows.yml
git commit -m "CI: lint + testy na Linuksie, cache, weryfikacja artefaktu"
git push
```

## Co daje nowa wersja

| Zmiana | Po co |
|---|---|
| `on: push` / `pull_request` | CI rusza samo, nie trzeba klikać ręcznie |
| job `checks` (Linux) | ruff + samotest w ~2 min, zanim ruszy wolny build Windows |
| `cache: pip` | szybsza instalacja zależności |
| cache modelu VOSK | build nie zależy od zawodnego mirrora `alphacephei.com` |
| `NIXI_REQUIRE_ACOUSTICS=1` | brak modelu/głosu to błąd CI, nie ciche pominięcie |
| `concurrency` | starszy bieg tej samej gałęzi jest anulowany |
| `timeout-minutes` | zawieszony bieg nie zżera minut |
| krok *Verify artifact* | sprawdza rozmiar `.exe` i odpala smoke test `--version` |
