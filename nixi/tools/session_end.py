"""Narzędzie end_session."""
from __future__ import annotations


def end_session(args: dict, ctx) -> dict:
    hook = ctx.hooks.get("request_end")
    if hook is not None:
        try:
            hook("goodbye")
        except Exception:  # noqa: BLE001
            pass
    return {"status": "ok", "output": "Sesja zakończona. Do usłyszenia!"}
