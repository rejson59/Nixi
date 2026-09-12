"""Rejestr narzędzi (function calling) Nixi.

Każde narzędzie ma: schemat (Google functionDeclarations), politykę potwierdzeń
i funkcję execute(args, ctx) → {"status": "ok"/"error", "output": str}.
"""
from __future__ import annotations

import io
import logging
from dataclasses import dataclass
from typing import Callable

from .. import config as config_mod


@dataclass
class Tool:
    name: str
    description: str
    parameters: dict
    execute: Callable
    confirm: str = "never"   # "never" | "risky"
    timeout: float = 20.0


class ToolContext:
    """Kontekst wykonania: pamięć, ustawienia, hooki do sesji."""

    def __init__(self, settings: config_mod.AppSettings, memory, logger: logging.Logger | None = None):
        self.settings = settings
        self.memory = memory
        self.log = logger or logging.getLogger("nixi.tools")
        self.hooks: dict = {}   # send_video, request_end, ui_note

    # ------------------------------------------------------------- zrzut ekranu
    def capture_screenshot(self) -> bytes | None:
        """JPEG całego pulpitu (wszystkie monitory), skalowany do max_width."""
        try:
            import mss
            from PIL import Image

            with mss.mss() as sct:
                monitors = sct.monitors  # [0] = całość, [1..] = pojedyncze
                use = monitors[1:] if len(monitors) > 2 else monitors[1:2]
                images = []
                for m in use:
                    shot = sct.grab(m)
                    img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
                    images.append(img)
                if not images:
                    return None
                base = images[0]
                if len(images) > 1:
                    widths = [im.width for im in images]
                    heights = [im.height for im in images]
                    canvas = Image.new("RGB", (sum(widths), max(heights)))
                    x = 0
                    for im in images:
                        canvas.paste(im, (x, 0))
                        x += im.width
                    base = canvas
                max_w = int(self.settings.vision.max_width)
                if base.width > max_w:
                    base = base.resize((max_w, int(base.height * max_w / base.width)), Image.LANCZOS)
                buf = io.BytesIO()
                base.save(buf, "JPEG", quality=int(self.settings.vision.jpeg_quality))
                return buf.getvalue()
        except Exception as e:  # noqa: BLE001
            self.log.warning("Zrzut ekranu niedostępny: %s", e)
            return None

    # ------------------------------------------------------- funkcje pomocnicze
    def function_declarations(self) -> list[dict]:
        out = []
        for t in TOOLS:
            out.append({
                "name": t.name,
                "description": t.description,
                "parameters": t.parameters,
            })
        return out

    def get_tool(self, name: str) -> Tool | None:
        for t in TOOLS:
            if t.name == name:
                return t
        return None


def _p(props: dict, required: list[str] | None = None) -> dict:
    return {"type": "OBJECT", "properties": props, "required": required or list(props.keys())}


def _str(desc: str) -> dict:
    return {"type": "STRING", "description": desc}


def _int(desc: str) -> dict:
    return {"type": "INTEGER", "description": desc}


def _num(desc: str) -> dict:
    return {"type": "NUMBER", "description": desc}


# Importy wykonywane na końcu (unikanie cykli importów)
def _load_tools() -> list[Tool]:
    from . import apps, browser, notes_memory, screen_control, system_power, volume, screenshot, session_end
    out: list[Tool] = []
    out.append(Tool("search_web", "Wyszukaj zapytanie w internecie (otwiera przeglądarkę z wynikami Google).",
                    _p({"query": _str("Zapytanie do wyszukania.")}), browser.search_web, confirm="never", timeout=10))
    out.append(Tool("open_website", "Otwórz podany adres URL w domyślnej przeglądarce.",
                    _p({"url": _str("Pełny adres URL, np. https://…")}), browser.open_website, confirm="never", timeout=10))
    out.append(Tool("open_app", "Otwórz aplikację po nazwie (np. 'notatnik', 'chrome', 'kalkulator').",
                    _p({"name": _str("Nazwa aplikacji.")}), apps.open_app, confirm="never", timeout=15))
    out.append(Tool("close_app", "Zamknij uruchomioną aplikację po nazwie procesu (np. 'chrome').",
                    _p({"name": _str("Nazwa aplikacji/procesu.")}), apps.close_app, confirm="risky", timeout=15))
    out.append(Tool("set_volume", "Ustaw głośność systemową (0-100) albo 'mute'/'unmute'.",
                    _p({"level": _str("Liczba 0-100, 'mute' lub 'unmute'.")}), volume.set_volume, confirm="never", timeout=10))
    out.append(Tool("set_brightness", "Ustaw jasność ekranu laptopa (0-100).",
                    _p({"level": _int("Poziom jasności 0-100.")}), system_power.set_brightness, confirm="never", timeout=10))
    out.append(Tool("take_screenshot", "Zrób i wyślij zrzut ekranu, aby zobaczyć co jest na ekranie.",
                    _p({}), screenshot.take_screenshot, confirm="never", timeout=15))
    out.append(Tool("move_mouse", "Przesuń kursor myszy do punktu (x, y) w pikselach ekranu.",
                    _p({"x": _int("Współrzędna X."), "y": _int("Współrzędna Y.")}), screen_control.move_mouse,
                    confirm="never", timeout=10))
    out.append(Tool("click_at", "Kliknij lewym/prawym przyciskiem w punkcie (x, y) ekranu.",
                    _p({"x": _int("Współrzędna X."), "y": _int("Współrzędna Y."),
                        "button": _str("'left' lub 'right' (domyślnie left)."),
                        "clicks": _int("Liczba kliknięć (domyślnie 1).")}, required=["x", "y"]),
                    screen_control.click_at, confirm="risky", timeout=10))
    out.append(Tool("scroll", "Przewiń kółkiem myszy (dodatnia liczba = w dół).",
                    _p({"amount": _int("Liczba 'kliknięć' kółka, np. 3 lub -3.")}), screen_control.scroll,
                    confirm="risky", timeout=10))
    out.append(Tool("type_text", "Wpisz tekst w aktualnie aktywnym oknie (jak pisanie na klawiaturze).",
                    _p({"text": _str("Tekst do wpisania.")}), screen_control.type_text, confirm="risky", timeout=20))
    out.append(Tool("press_keys", "Wciśnij kombinację klawiszy, np. 'ctrl+s', 'alt+f4', 'win+e'.",
                    _p({"combo": _str("Kombinacja klawiszy oddzielona '+', np. 'ctrl+shift+esc'.")}),
                    screen_control.press_keys, confirm="risky", timeout=10))
    out.append(Tool("foreground_app", "Sprawdź, jaka aplikacja jest aktualnie na wierzchu.",
                    _p({}), screen_control.foreground_app, confirm="never", timeout=10))
    out.append(Tool("remember_memory", "Zapisz ważny fakt o użytkowniku w pamięci długotrwałej.",
                    _p({"content": _str("Fakt do zapamiętania."),
                        "category": _str("Kategoria: fact/preference/plan/info (domyślnie fact).")},
                       required=["content"]), notes_memory.remember_memory, confirm="never", timeout=15))
    out.append(Tool("recall_memory", "Przypomnij wcześniej zapamiętane informacje o użytkowniku.",
                    _p({"query": _str("Czego dotyczy wspomnienie."),
                        "k": _int("Maksymalna liczba wyników (domyślnie 5).")}, required=["query"]),
                    notes_memory.recall_memory, confirm="never", timeout=15))
    out.append(Tool("create_note", "Utwórz notatkę (zapisuje się trwale w pamięci).",
                    _p({"title": _str("Tytuł notatki."), "content": _str("Treść notatki.")}),
                    notes_memory.create_note, confirm="never", timeout=10))
    out.append(Tool("read_notes", "Odczytaj ostatnie notatki.",
                    _p({"limit": _int("Liczba notatek (domyślnie 5).")}, required=[]),
                    notes_memory.read_notes, confirm="never", timeout=10))
    out.append(Tool("system_info", "Informacje o systemie: wersja, CPU, RAM, wolne miejsce, bateria.",
                    _p({}), system_power.system_info, confirm="never", timeout=15))
    out.append(Tool("control_system", "Sterowanie systemem: 'lock' (blokada), 'sleep' (uśpienie), 'shutdown', 'restart', 'abort'.",
                    _p({"action": _str("Jedno z: lock, sleep, shutdown, restart, abort.")}),
                    system_power.control_system, confirm="risky", timeout=20))
    out.append(Tool("end_session", "Zakończ rozmowę i zamknij nasłuch (użyj gdy użytkownik się żegna lub każe przestać).",
                    _p({}), session_end.end_session, confirm="never", timeout=5))
    return out


TOOLS: list[Tool] = _load_tools()
