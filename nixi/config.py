"""Konfiguracja aplikacji Nixi — dataclasses + zapis/odczyt JSON z głębokim scalaniem."""
from __future__ import annotations

import copy
import json
import logging
import os
import re
from dataclasses import asdict, dataclass, field, fields
from pathlib import Path
from typing import Any

from . import paths

DEFAULT_MODEL = "gemini-3.1-flash-live-preview"

DEFAULT_WAKE_PHRASES = [
    r"\bhej\s+nixi\b",
    r"\bhey\s+nixi\b",
    r"\bhej\s+niki\b",
    r"\bhej\s+nicki\b",
    r"\bhej\s+nixy\b",
    r"\bhej\s+nisi\b",
    r"\bej\s+nixi\b",
]

# Kluczowe frazy pożegnania — po nich Nixi kończy sesję i zamyka WebSocket.
GOODBYE_PATTERNS = [
    r"\bdo widzenia\b", r"\bdobranoc\b", r"\bdo usłyszenia\b", r"\bna razie\b",
    r"\bpa\s*pa\b", r"\bpapa\b", r"\bżegnaj\b", r"\bzegnaj\b", r"\bkoniec\b",
    r"\bwystarczy\b", r"\bprzestań\s+słuchać\b", r"\bprzestan\s+sluchac\b",
    r"\bwyłącz\s+się\b", r"\bwylacz\s+sie\b", r"\bwyłącz\s+nasłuch\b",
    r"\bdość\b", r"\bdosc\b", r"\bdziękuję\s+to\s+wszystko\b", r"\bdziekuje\s+to\s+wszystko\b",
    r"\bto\s+tyle\b", r"\bkończymy\b", r"\bkonczymy\b",
]

# Frazy zgody / odmowy dla akcji wymagających potwierdzenia.
CONSENT_PATTERNS = [
    r"\btak\b", r"\bjasne\b", r"\bok\b", r"\bokej\b", r"\bokay\b", r"\bzrób\s+to\b",
    r"\bzrob\s+to\b", r"\bwykonaj\b", r"\bproszę\b", r"\bprosze\b", r"\bpewnie\b",
    r"\bzgoda\b", r"\bdobra\b", r"\bdobrze\b", r"\bmożesz\b", r"\bmozesz\b",
]
DENY_PATTERNS = [
    r"\bnie\b", r"\bnie\s+chcę\b", r"\bnie\s+chce\b", r"\bnie\s+rób\b", r"\bnie\s+rob\b",
    r"\banuluj\b", r"\bzostaw\b", r"\bzostaw\s+to\b", r"\bprzerwij\b", r"\bstop\b",
    r"\bwstrzymaj\b", r"\bzapomnij\b", r"\bnieważne\b", r"\bniewazne\b",
]


@dataclass
class WakeSettings:
    enabled: bool = True
    phrases: list[str] = field(default_factory=lambda: list(DEFAULT_WAKE_PHRASES))
    allow_bare_nixi: bool = False
    cooldown_s: float = 2.5
    vosk_model: str = "vosk-model-small-pl-0.22"
    vosk_model_dir: str = ""  # nadpisanie ścieżki (opcjonalne)


@dataclass
class SessionSettings:
    silence_timeout_s: float = 45.0
    wake_grace_s: float = 8.0
    max_duration_s: float = 1200.0
    reconnect_at_s: float = 510.0  # przed limitem ~10 min połączenia Live API


@dataclass
class SafetySettings:
    confirm_risky: bool = True
    confirm_all: bool = False
    confirm_typing: bool = True


@dataclass
class VisionSettings:
    enabled: bool = True
    interval_s: float = 6.0
    max_frames: int = 12
    max_width: int = 1280
    jpeg_quality: int = 80


@dataclass
class MemorySettings:
    enabled: bool = True
    top_k: int = 6
    embed_model: str = "text-embedding-004"
    auto_remember: bool = True


@dataclass
class UISettings:
    transcript: bool = True
    memories_feed: bool = True
    shader: bool = True


@dataclass
class HotkeySettings:
    toggle_listen: str = "ctrl+shift+alt+n"
    end_session: str = "ctrl+shift+alt+esc"
    quit: str = "ctrl+shift+alt+q"


@dataclass
class SystemSettings:
    autostart: bool = False


@dataclass
class AppSettings:
    model: str = DEFAULT_MODEL
    language: str = "pl"
    assistant_name: str = "Nixi"
    user_name: str = ""
    api_key: str = ""
    wake: WakeSettings = field(default_factory=WakeSettings)
    session: SessionSettings = field(default_factory=SessionSettings)
    safety: SafetySettings = field(default_factory=SafetySettings)
    vision: VisionSettings = field(default_factory=VisionSettings)
    memory: MemorySettings = field(default_factory=MemorySettings)
    ui: UISettings = field(default_factory=UISettings)
    hotkeys: HotkeySettings = field(default_factory=HotkeySettings)
    system: SystemSettings = field(default_factory=SystemSettings)


_LOG = logging.getLogger("nixi.config")


# Sekcje zagnieżdżone są jawnie mapowane, aby ustawienia z przyszłych wersji
# aplikacji nie powodowały awarii przy wczytywaniu starszego pliku JSON.
_SECTION_TYPES = {
    "wake": WakeSettings,
    "session": SessionSettings,
    "safety": SafetySettings,
    "vision": VisionSettings,
    "memory": MemorySettings,
    "ui": UISettings,
    "hotkeys": HotkeySettings,
    "system": SystemSettings,
}


def _merge(base: dict, override: dict) -> dict:
    """Scal słowniki bez modyfikowania argumentów wejściowych."""
    out = copy.deepcopy(base)
    if not isinstance(override, dict):
        return out
    for k, v in override.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


def _load_json(path: Path) -> dict:
    """Wczytaj słownik JSON; uszkodzony plik nie blokuje uruchomienia aplikacji."""
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            return data
        _LOG.warning("Plik ustawień %s nie zawiera obiektu JSON — używam domyślnych.", path)
    except FileNotFoundError:
        return {}
    except (OSError, json.JSONDecodeError) as exc:
        _LOG.warning("Nie można wczytać ustawień %s: %s — używam domyślnych.", path, exc)
    return {}


def _coerce_value(default: Any, value: Any) -> Any:
    """Ograniczona normalizacja wartości z ręcznie edytowanego JSON-a."""
    if value is None:
        return default
    if isinstance(default, bool):
        if isinstance(value, str):
            return value.strip().lower() in {"1", "true", "yes", "tak", "on"}
        return value if isinstance(value, bool) else bool(value)
    if isinstance(default, int) and not isinstance(default, bool):
        try:
            return int(value)
        except (TypeError, ValueError):
            return default
    if isinstance(default, float):
        try:
            return float(value)
        except (TypeError, ValueError):
            return default
    if isinstance(default, str):
        return str(value)
    if isinstance(default, list):
        return value if isinstance(value, list) else default
    return value


def _section_kwargs(cls, value: Any) -> dict:
    """Zwróć znane pola sekcji i bezpiecznie obsłuż śmieci w JSON."""
    if not isinstance(value, dict):
        return {}
    defaults = cls()
    return {
        f.name: _coerce_value(getattr(defaults, f.name), value[f.name])
        for f in fields(cls)
        if f.name in value
    }


def settings_from_dict(data: dict | None) -> AppSettings:
    """Zbuduj ustawienia z JSON, zachowując wartości domyślne i kompatybilność.

    Ta funkcja jest wspólna dla startu aplikacji i formularza QML. Dzięki temu
    częściowo zapisany albo rozszerzony w przyszłości plik nie kończy programu
    wyjątkiem ``TypeError``.
    """
    default_settings = AppSettings()
    defaults = asdict(default_settings)
    merged = _merge(defaults, data if isinstance(data, dict) else {})
    top_fields = {f.name for f in fields(AppSettings)} - set(_SECTION_TYPES)
    top = {
        f.name: _coerce_value(getattr(default_settings, f.name), merged[f.name])
        for f in fields(AppSettings)
        if f.name in top_fields and f.name in merged
    }
    settings = AppSettings(**top)
    for name, cls in _SECTION_TYPES.items():
        setattr(settings, name, cls(**_section_kwargs(cls, merged.get(name))))
    return settings


def load_settings() -> AppSettings:
    data = _load_json(paths.settings_path())
    # lokalne nadpisania (przede wszystkim klucz API) mają wyższy priorytet
    local = _load_json(paths.settings_local_path())
    return settings_from_dict(_merge(data, local))


def _write_json_atomic(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.tmp")
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")
        os.replace(tmp, path)
        # Na systemach uniksowych klucz pozostaje czytelny wyłącznie dla
        # właściciela. Windows ignoruje chmod, ale nie szkodzi go wykonać.
        if path.name == "settings.local.json":
            try:
                path.chmod(0o600)
            except OSError:
                pass
    finally:
        try:
            tmp.unlink(missing_ok=True)
        except OSError:
            pass


def save_settings(settings: AppSettings, local: bool = False) -> None:
    """Zapisz ustawienia bez umieszczania klucza API w zwykłym JSON-ie.

    ``settings.json`` zawiera preferencje aplikacji, a ``settings.local.json``
    wyłącznie sekret. Dla wygody zwykłe ``save_settings(settings)`` zapisuje
    oba pliki, więc wywołujący nie może przypadkiem utrwalić klucza w głównym
    pliku ustawień.
    """
    if local:
        _write_json_atomic(paths.settings_local_path(), {"api_key": settings.api_key.strip()})
        return

    data = asdict(settings)
    data.pop("api_key", None)
    _write_json_atomic(paths.settings_path(), data)
    save_settings(settings, local=True)


_API_KEY_RE = re.compile(r"^AIza[0-9A-Za-z_\-]{20,}$")


def validate_api_key(key: str) -> tuple[bool, str]:
    key = (key or "").strip()
    if not key:
        return False, "Brak klucza API."
    if not _API_KEY_RE.match(key):
        return False, (
            "Klucz wygląda na nieprawidłowy — klucze Gemini API zaczynają się od „AIza” "
            "(https://aistudio.google.com/apikey)."
        )
    return True, "OK"


def resolve_api_key(settings: AppSettings) -> tuple[str, str]:
    """Zwróć (klucz, źródło): local → GEMINI_API_KEY → ustawienia legacy."""
    local = _load_json(paths.settings_local_path())
    local_key = str((local or {}).get("api_key") or "").strip()
    if local_key:
        return local_key, "local"
    env = os.environ.get("GEMINI_API_KEY", "").strip()
    if env:
        return env, "env"
    # Wspieramy stare settings.json, aby aktualizacja nie wyłączyła istniejącej
    # konfiguracji. Przy następnym zapisie klucz zostanie przeniesiony lokalnie.
    legacy_key = str(settings.api_key or "").strip()
    if legacy_key:
        return legacy_key, "settings"
    return "", "none"
