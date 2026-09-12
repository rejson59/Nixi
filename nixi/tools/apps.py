"""Otwieranie i zamykanie aplikacji (Windows)."""
from __future__ import annotations

import difflib
import os
import subprocess
import sys


def _ok(output: str, **extra) -> dict:
    return {"status": "ok", "output": output, **extra}


def _err(output: str) -> dict:
    return {"status": "error", "output": output}


COMMON_APPS = {
    "notatnik": "notepad", "notepad": "notepad",
    "kalkulator": "calc", "calc": "calc",
    "chrome": "chrome", "google chrome": "chrome",
    "edge": "msedge", "microsoft edge": "msedge",
    "firefox": "firefox", "opera": "opera", "brave": "brave",
    "word": "winword", "excel": "excel", "powerpoint": "powerpnt",
    "outlook": "outlook", "paint": "mspaint",
    "eksplorator": "explorer", "explorer": "explorer",
    "terminal": "wt", "windows terminal": "wt", "cmd": "cmd", "powershell": "powershell",
    "vscode": "code", "visual studio code": "code",
    "spotify": "spotify", "steam": "steam", "discord": "discord", "telegram": "telegram",
    "zrzut": "snippingtool", "wycinanie": "snippingtool",
}


def _start_menu_lnk_dirs() -> list[str]:
    dirs = []
    pd = os.environ.get("PROGRAMDATA")
    if pd:
        dirs.append(os.path.join(pd, "Microsoft", "Windows", "Start Menu", "Programs"))
    ad = os.environ.get("APPDATA")
    if ad:
        dirs.append(os.path.join(ad, "Microsoft", "Windows", "Start Menu", "Programs"))
    return dirs


def _find_lnk(name: str) -> str | None:
    """Znajdź skrót .lnk w menu Start (dopasowanie rozmyte)."""
    candidates: dict[str, str] = {}
    name_l = name.lower()
    for d in _start_menu_lnk_dirs():
        for root, _dirs, files in os.walk(d):
            for f in files:
                if f.lower().endswith(".lnk"):
                    base = f[:-4].lower()
                    candidates[base] = os.path.join(root, f)
    if name_l in candidates:
        return candidates[name_l]
    for base, path in candidates.items():
        if name_l in base or base in name_l:
            return path
    close = difflib.get_close_matches(name_l, list(candidates.keys()), n=1, cutoff=0.62)
    if close:
        return candidates[close[0]]
    return None


def open_app(args: dict, ctx) -> dict:
    if sys.platform != "win32":
        return _err("Otwieranie aplikacji działa na Windows (wersja .exe).")
    name = (args.get("name") or "").strip()
    if not name:
        return _err("Podaj nazwę aplikacji.")
    try:
        target = None
        name_l = name.lower()
        for k, v in COMMON_APPS.items():
            if k == name_l or k in name_l or name_l in k:
                target = v
                break
        if target:
            subprocess.Popen(["cmd", "/c", "start", "", target], shell=False)
            return _ok(f"Otwarto: {target}")
        lnk = _find_lnk(name)
        if lnk:
            os.startfile(lnk)  # type: ignore[attr-defined]
            return _ok(f"Otwarto: {os.path.basename(lnk)}")
        # ostatnia deska ratunku — Windows rozwiąże nazwę przez App Paths
        subprocess.Popen(f'start "" "{name}"', shell=True)
        return _ok(f"Próbuję otworzyć: {name}")
    except Exception as e:  # noqa: BLE001
        return _err(f"Nie udało się otworzyć „{name}”: {e}")


def close_app(args: dict, ctx) -> dict:
    if sys.platform != "win32":
        return _err("Zamykanie aplikacji działa na Windows (wersja .exe).")
    name = (args.get("name") or "").strip().lower()
    if not name:
        return _err("Podaj nazwę aplikacji.")
    try:
        out = subprocess.run(["tasklist", "/FO", "CSV", "/NH"], capture_output=True, text=True, timeout=10)
        procs: dict[str, str] = {}
        for line in out.stdout.splitlines():
            parts = [p.strip('"') for p in line.split('","')]
            if len(parts) >= 2:
                exe = parts[0]
                procs[exe.lower()] = exe
        matches = [exe for key, exe in procs.items() if name in key]
        if not matches:
            return _err(f"Nie znaleziono uruchomionego procesu „{name}”.")
        exe = matches[0]
        r = subprocess.run(["taskkill", "/IM", exe], capture_output=True, text=True, timeout=15)
        if r.returncode == 0:
            return _ok(f"Zamknięto aplikację: {exe}")
        return _err(f"Nie udało się zamknąć {exe}: {r.stderr.strip()}")
    except Exception as e:  # noqa: BLE001
        return _err(f"Błąd zamykania aplikacji: {e}")
