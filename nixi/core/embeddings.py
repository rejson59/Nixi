"""Semantyczne osadzenia pamięci przez Gemini Embeddings API (opcjonalne).

Jeśli klucz API jest dostępny — zapytania recall() mogą korzystać z podobieństwa
cosinusowego; w razie braku sieci/klucza zawsze działa FTS5.
"""
from __future__ import annotations

import json
import logging
import struct
import urllib.request

_EMBED_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:embedContent?key={key}"


def embed_text(api_key: str, model: str, text: str, logger: logging.Logger | None = None,
               timeout_s: float = 10.0) -> list[float] | None:
    log = logger or logging.getLogger("nixi.memory")
    if not api_key or not text:
        return None
    payload = json.dumps({"model": f"models/{model}", "content": {"parts": [{"text": text[:4000]}]}}).encode()
    req = urllib.request.Request(
        _EMBED_URL.format(model=model, key=api_key),
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as r:
            data = json.loads(r.read().decode("utf-8"))
        values = data["embedding"]["values"]
        return [float(v) for v in values]
    except Exception as e:  # noqa: BLE001
        log.warning("Embedding niedostępny (%s) — fallback do FTS.", e)
        return None


def pack_embedding(values: list[float]) -> bytes:
    return struct.pack(f"<{len(values)}f", *values)


def unpack_embedding(blob: bytes) -> list[float]:
    return list(struct.unpack(f"<{len(blob) // 4}f", blob))


def cosine(a: list[float], b: list[float]) -> float:
    if len(a) != len(b) or not a:
        return 0.0
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    na = sum(x * x for x in a) ** 0.5
    nb = sum(y * y for y in b) ** 0.5
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)
