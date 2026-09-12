"""Otwieranie stron i wyszukiwanie w internecie."""
from __future__ import annotations

import webbrowser
from urllib.parse import quote, urlparse


def _ok(output: str, **extra) -> dict:
    return {"status": "ok", "output": output, **extra}


def _err(output: str) -> dict:
    return {"status": "error", "output": output}


def search_web(args: dict, ctx) -> dict:
    query = (args.get("query") or "").strip()
    if not query:
        return _err("Brak zapytania.")
    url = f"https://www.google.com/search?q={quote(query)}"
    try:
        webbrowser.open(url)
        return _ok(f"Otwarto wyniki wyszukiwania: {query}", url=url)
    except Exception as e:  # noqa: BLE001
        return _err(f"Nie udało się otworzyć przeglądarki: {e}")


def open_website(args: dict, ctx) -> dict:
    url = (args.get("url") or "").strip()
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        return _err("Nieprawidłowy adres URL (wymagany https://…).")
    try:
        webbrowser.open(url)
        return _ok(f"Otwarto stronę: {url}", url=url)
    except Exception as e:  # noqa: BLE001
        return _err(f"Nie udało się otworzyć strony: {e}")
