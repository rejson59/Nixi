"""Ścieżki danych i zasobów aplikacji Nixi (działa też po PyInstaller)."""
from __future__ import annotations

import os
import sys
from pathlib import Path

APP_NAME = "Nixi"
APP_SLUG = "nixi"


def _frozen_dir() -> Path | None:
    return Path(getattr(sys, "_MEIPASS", None)) if getattr(sys, "frozen", False) else None


def repo_root() -> Path:
    """Katalog projektu (źródła) — tylko w trybie deweloperskim."""
    return Path(__file__).resolve().parent.parent


def resource_dir() -> Path:
    """Katalog zasobów (działa w trybie źródłowym i po PyInstaller)."""
    if _frozen_dir() is not None:
        return _frozen_dir()
    return repo_root()


def data_dir() -> Path:
    """Katalog danych użytkownika (ustawienia, baza, logi, modele)."""
    if sys.platform == "win32":
        base = os.environ.get("LOCALAPPDATA") or os.environ.get("APPDATA") or str(Path.home())
        d = Path(base) / APP_NAME
    else:
        base = os.environ.get("XDG_DATA_HOME") or str(Path.home() / ".local" / "share")
        d = Path(base) / APP_SLUG
    d.mkdir(parents=True, exist_ok=True)
    return d


def cache_dir() -> Path:
    if sys.platform == "win32":
        base = os.environ.get("LOCALAPPDATA") or str(data_dir())
        d = Path(base) / APP_NAME / "cache"
    else:
        base = os.environ.get("XDG_CACHE_HOME") or str(Path.home() / ".cache")
        d = Path(base) / APP_SLUG
    d.mkdir(parents=True, exist_ok=True)
    return d


def logs_dir() -> Path:
    d = data_dir() / "logs"
    d.mkdir(parents=True, exist_ok=True)
    return d


def models_dir() -> Path:
    d = data_dir() / "models"
    d.mkdir(parents=True, exist_ok=True)
    return d


def db_path() -> Path:
    return data_dir() / "nixi.db"


def settings_path() -> Path:
    return data_dir() / "settings.json"


def settings_local_path() -> Path:
    """Lokalne nadpisania (np. klucz API) — NIGDY nie commitować."""
    return data_dir() / "settings.local.json"


def vosk_model_path(model_name: str = "vosk-model-small-pl-0.22") -> Path:
    return models_dir() / model_name


def ui_qml_path() -> Path:
    return resource_dir() / "nixi" / "ui"


def assets_path() -> Path:
    return resource_dir() / "nixi" / "assets"
