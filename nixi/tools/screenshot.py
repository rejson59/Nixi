"""Narzędzie take_screenshot — wysyła zrzut ekranu do modelu."""
from __future__ import annotations


def take_screenshot(args: dict, ctx) -> dict:
    jpeg = ctx.capture_screenshot()
    if not jpeg:
        return {"status": "error", "output": "Nie udało się zrobić zrzutu ekranu."}
    hook = ctx.hooks.get("send_video")
    if hook is not None:
        try:
            hook(jpeg)
            return {"status": "ok", "output": "Zrzut ekranu wysłany. Przeanalizuj widok i opisz użytkownikowi co widzisz."}
        except Exception as e:  # noqa: BLE001
            return {"status": "error", "output": f"Nie udało się wysłać zrzutu: {e}"}
    return {"status": "ok", "output": "Zrzut ekranu gotowy."}
