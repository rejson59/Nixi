"""Sterowanie myszą i klawiaturą (Windows — pywin32 / ctypes).

Wszystkie współrzędne w pikselach ekranu (wirtualny pulpit — działa na wielu
monitorach). Na innych systemach narzędzia zwracają czytelny błąd.
"""
from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import re
import subprocess
import sys
import time

_VK_MAP: dict[str, int] = {}


def _ok(output: str, **extra) -> dict:
    return {"status": "ok", "output": output, **extra}


def _err(output: str) -> dict:
    return {"status": "error", "output": output}


def _need_windows() -> dict | None:
    if sys.platform == "win32":
        return None
    return _err("Sterowanie ekranem działa tylko na Windows (wersja .exe).")


def _screen_size() -> tuple[int, int]:
    """Rozmiar wirtualnego pulpitu (SM_CXVIRTUALSCREEN)."""
    user32 = ctypes.windll.user32
    return int(user32.GetSystemMetrics(76)), int(user32.GetSystemMetrics(77))


def _clamp_xy(x, y) -> tuple[int, int]:
    w, h = _screen_size()
    return int(max(0, min(w - 1, float(x)))), int(max(0, min(h - 1, float(y))))


def move_mouse(args: dict, ctx) -> dict:
    err = _need_windows()
    if err:
        return err
    try:
        x, y = _clamp_xy(float(args.get("x", 0)), float(args.get("y", 0)))
        ctypes.windll.user32.SetCursorPos(x, y)
        return _ok(f"Kursor przesunięty do ({x}, {y}).", x=x, y=y)
    except Exception as e:  # noqa: BLE001
        return _err(f"Błąd myszy: {e}")


def click_at(args: dict, ctx) -> dict:
    err = _need_windows()
    if err:
        return err
    import win32api
    import win32con

    try:
        x, y = _clamp_xy(float(args.get("x", 0)), float(args.get("y", 0)))
        button = (args.get("button") or "left").lower()
        clicks = max(1, min(3, int(args.get("clicks") or 1)))
        ctypes.windll.user32.SetCursorPos(x, y)
        time.sleep(0.08)
        down, up = (win32con.MOUSEEVENTF_LEFTDOWN, win32con.MOUSEEVENTF_LEFTUP)
        if button == "right":
            down, up = (win32con.MOUSEEVENTF_RIGHTDOWN, win32con.MOUSEEVENTF_RIGHTUP)
        elif button == "middle":
            down, up = (win32con.MOUSEEVENTF_MIDDLEDOWN, win32con.MOUSEEVENTF_MIDDLEUP)
        elif button != "left":
            return _err(f"Nieznany przycisk: {button}")
        for _ in range(clicks):
            win32api.mouse_event(down, 0, 0, 0, 0)
            time.sleep(0.03)
            win32api.mouse_event(up, 0, 0, 0, 0)
            time.sleep(0.06)
        return _ok(f"Kliknięto ({x}, {y}) {button} ×{clicks}.", x=x, y=y)
    except Exception as e:  # noqa: BLE001
        return _err(f"Błąd klikania: {e}")


def scroll(args: dict, ctx) -> dict:
    err = _need_windows()
    if err:
        return err
    import win32api
    import win32con

    try:
        amount = int(args.get("amount") or 0)
        amount = max(-50, min(50, amount))
        if amount == 0:
            return _ok("Brak przewijania.")
        win32api.mouse_event(win32con.MOUSEEVENTF_WHEEL, 0, 0, amount * 120, 0)
        return _ok(f"Przewinięto o {amount}.")
    except Exception as e:  # noqa: BLE001
        return _err(f"Błąd przewijania: {e}")


def _ensure_vk_map() -> None:
    if _VK_MAP:
        return
    import win32con

    for name in dir(win32con):
        if name.startswith("VK_"):
            try:
                _VK_MAP[name[3:].lower()] = int(getattr(win32con, name))
            except Exception:  # noqa: BLE001
                pass
    # aliasy
    for alias, real in {
        "ctrl": "control", "control": "control", "alt": "menu", "menu": "menu",
        "win": "lwin", "windows": "lwin", "esc": "escape", "escape": "escape",
        "enter": "return", "return": "return", "del": "delete", "delete": "delete",
        "space": "space", "tab": "tab", "up": "up", "down": "down", "left": "left",
        "right": "right", "home": "home", "end": "end", "pageup": "prior",
        "pagedown": "next", "backspace": "back", "capslock": "capital",
        "printscreen": "snapshot", "scrolllock": "scroll", "numlock": "numlock",
    }.items():
        if alias not in _VK_MAP and real in _VK_MAP:
            _VK_MAP[alias] = _VK_MAP[real]
    # pojedyncze znaki
    for ch in "abcdefghijklmnopqrstuvwxyz0123456789-=[];',./`\\":
        if ch not in _VK_MAP:
            _VK_MAP[ch] = ord(ch.upper())
    for ch, vk in {"-": 0xBD, "=": 0xBB, "[": 0xDB, "]": 0xDD, ";": 0xBA,
                   "'": 0xDE, ",": 0xBC, ".": 0xBE, "/": 0xBF, "`": 0xC0, "\\": 0xDC}.items():
        _VK_MAP[ch] = vk


def press_keys(args: dict, ctx) -> dict:
    err = _need_windows()
    if err:
        return err
    import win32api
    import win32con

    try:
        _ensure_vk_map()
        combo = (args.get("combo") or "").strip()
        if not combo:
            return _err("Brak kombinacji klawiszy.")
        keys = [k.strip().lower() for k in re.split(r"[+,]", combo) if k.strip()]
        vks: list[int] = []
        for k in keys:
            if k in ("shift", "ctrl", "control", "alt", "menu", "win", "lwin", "rwin"):
                vks.append(_VK_MAP.get(k, 0))
            elif len(k) == 1 and k in _VK_MAP:
                vks.append(_VK_MAP[k])
            elif k in _VK_MAP:
                vks.append(_VK_MAP[k])
            else:
                return _err(f"Nieznany klawisz: {k}")
        if not vks:
            return _err("Nie rozpoznano klawiszy.")
        for vk in vks:
            win32api.keybd_event(vk, 0, 0, 0)
        time.sleep(0.04)
        for vk in reversed(vks):
            win32api.keybd_event(vk, 0, win32con.KEYEVENTF_KEYUP, 0)
        return _ok(f"Wciśnięto: {combo}")
    except Exception as e:  # noqa: BLE001
        return _err(f"Błąd klawiatury: {e}")


def type_text(args: dict, ctx) -> dict:
    err = _need_windows()
    if err:
        return err
    text = args.get("text") or ""
    if not text:
        return _err("Brak tekstu do wpisania.")
    # 1) preferowana droga: schowek + Ctrl+V (obsługuje polskie znaki)
    try:
        import win32clipboard
        import win32con

        win32clipboard.OpenClipboard()
        try:
            win32clipboard.EmptyClipboard()
            win32clipboard.SetClipboardText(text, win32clipboard.CF_UNICODETEXT)
        finally:
            win32clipboard.CloseClipboard()
        time.sleep(0.05)
        _ensure_vk_map()
        import win32api

        win32api.keybd_event(_VK_MAP["control"], 0, 0, 0)
        win32api.keybd_event(_VK_MAP["v"], 0, 0, 0)
        win32api.keybd_event(_VK_MAP["v"], 0, win32con.KEYEVENTF_KEYUP, 0)
        win32api.keybd_event(_VK_MAP["control"], 0, win32con.KEYEVENTF_KEYUP, 0)
        return _ok(f"Wpisano tekst ({len(text)} znaków).")
    except Exception:  # noqa: BLE001
        pass
    # 2) fallback: znak po znaku (tylko ASCII)
    import win32api
    import win32con

    try:
        _ensure_vk_map()
        for ch in text:
            low = ch.lower()
            if low in _VK_MAP:
                need_shift = ch.isupper() or ch in '~!@#$%^&*()_+{}|:"<>?'
                if need_shift:
                    win32api.keybd_event(_VK_MAP["shift"], 0, 0, 0)
                win32api.keybd_event(_VK_MAP[low], 0, 0, 0)
                win32api.keybd_event(_VK_MAP[low], 0, win32con.KEYEVENTF_KEYUP, 0)
                if need_shift:
                    win32api.keybd_event(_VK_MAP["shift"], 0, win32con.KEYEVENTF_KEYUP, 0)
                time.sleep(0.012)
            elif ch == "\n":
                win32api.keybd_event(_VK_MAP["return"], 0, 0, 0)
                win32api.keybd_event(_VK_MAP["return"], 0, win32con.KEYEVENTF_KEYUP, 0)
            # pozostałe (polskie znaki bez schowka) — pomijamy z komunikatem
        return _ok("Tekst wpisany (część znaków specjalnych mogła zostać pominięta).")
    except Exception as e:  # noqa: BLE001
        return _err(f"Nie udało się wpisać tekstu: {e}")


def foreground_app(args: dict, ctx) -> dict:
    err = _need_windows()
    if err:
        return err
    try:
        user32 = ctypes.windll.user32
        hwnd = user32.GetForegroundWindow()
        length = user32.GetWindowTextLengthW(hwnd)
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        title = buf.value or "(bez tytułu)"
        return _ok(f"Aktywne okno: {title}", title=title)
    except Exception as e:  # noqa: BLE001
        return _err(f"Nie udało się odczytać aktywnego okna: {e}")
