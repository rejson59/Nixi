"""Konfiguracja aplikacji Nixi — dataclasses + zapis/odczyt JSON z głębokim scalaniem."""
from __future__ import annotations

import copy
import json
import os
import re
from dataclasses import asdict, dataclass, field, fields
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


def _merge(base: dict, override: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in (override or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


def _load_json(path) -> dict:
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def load_settings() -> AppSettings:
    data = _load_json(paths.settings_path())
    # lokalne nadpisania (klucz API itd.) mają wyższy priorytet
    local = _load_json(paths.settings_local_path())
    merged = _merge(asdict(AppSettings()), _merge(data, local))
    s = AppSettings(**{k: merged[k] for k in fields(AppSettings) if k in merged})
    s.wake = WakeSettings(**merged["wake"])
    s.session = SessionSettings(**merged["session"])
    s.safety = SafetySettings(**merged["safety"])
    s.vision = VisionSettings(**merged["vision"])
    s.memory = MemorySettings(**merged["memory"])
    s.ui = UISettings(**merged["ui"])
    s.hotkeys = HotkeySettings(**merged["hotkeys"])
    s.system = SystemSettings(**merged["system"])
    return s


def save_settings(settings: AppSettings, local: bool = False) -> None:
    """Zapis do settings.json (local=True → settings.local.json, np. sam klucz API)."""
    path = paths.settings_local_path() if local else paths.settings_path()
    data = asdict(settings)
    if local:
        # w pliku lokalnym trzymamy tylko rzeczy "sekretne"
        data = {"api_key": settings.api_key}
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


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
    """Zwraca (klucz, źródło). Kolejność: settings.local.json → GEMINI_API_KEY → settings.json."""
    local = _load_json(paths.settings_local_path())
    if (local or {}).get("api_key", "").strip():
        return local["api_key"].strip(), "local"
    env = os.environ.get("GEMINI_API_KEY", "").strip()
    if env:
        return env, "env"
    if settings.api_key.strip():
        return settings.api_key.strip(), "settings"
    return "", "none"
