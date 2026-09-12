"""Głośność systemowa (Windows — pycaw)."""
from __future__ import annotations

import sys


def _ok(output: str, **extra) -> dict:
    return {"status": "ok", "output": output, **extra}


def _err(output: str) -> dict:
    return {"status": "error", "output": output}


def _get_volume_interface():
    """Zwraca interfejs IAudioEndpointVolume głośników (Windows)."""
    from ctypes import POINTER, cast

    from comtypes import CLSCTX_ALL
    from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume

    devices = AudioUtilities.GetSpeakers()
    interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    return cast(interface, POINTER(IAudioEndpointVolume))


def set_volume(args: dict, ctx) -> dict:
    if sys.platform != "win32":
        return _err("Sterowanie głośnością działa na Windows (wersja .exe).")
    level = str(args.get("level") or "").strip().lower()
    try:
        vol = _get_volume_interface()
        if level == "mute":
            vol.SetMute(1, None)
            return _ok("Dźwięk wyciszony.")
        if level in ("unmute", "odcisz"):
            vol.SetMute(0, None)
            return _ok("Wyciszenie wyłączone.")
        pct = float(level)
        pct = max(0.0, min(100.0, pct))
        vol.SetMasterVolumeLevelScalar(pct / 100.0, None)
        if pct == 0.0:
            vol.SetMute(1, None)
        else:
            vol.SetMute(0, None)
        return _ok(f"Głośność ustawiona na {pct:.0f}%.", level=pct)
    except Exception as e:  # noqa: BLE001
        return _err(f"Nie udało się zmienić głośności: {e}")


def get_volume(ctx) -> dict:
    if sys.platform != "win32":
        return _err("Niedostępne na tym systemie.")
    try:
        vol = _get_volume_interface()
        pct = round(vol.GetMasterVolumeLevelScalar() * 100)
        muted = bool(vol.GetMute())
        return _ok(f"Głośność: {pct}% {'(wyciszone)' if muted else ''}", level=pct, muted=muted)
    except Exception as e:  # noqa: BLE001
        return _err(f"Błąd odczytu głośności: {e}")
