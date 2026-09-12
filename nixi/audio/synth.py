"""Synteza krótkich dźwięków systemowych (bez plików — czysty numpy)."""
from __future__ import annotations

import numpy as np

RATE = 24000


def _tone(freq: float, dur: float, rate: int = RATE, harmonics: tuple = (1.0, 0.4, 0.15), decay: float = 5.0) -> np.ndarray:
    n = int(dur * rate)
    t = np.arange(n) / rate
    env = np.exp(-decay * t / max(dur, 1e-3))
    out = np.zeros(n, dtype=np.float32)
    for i, amp in enumerate(harmonics):
        out += (amp / max(1, i + 1)) * np.sin(2 * np.pi * freq * (i + 1) * t)
    out *= env / (np.max(np.abs(out)) + 1e-9)
    return out.astype(np.float32)


def _fade(x: np.ndarray, ms: float = 8.0, rate: int = RATE) -> np.ndarray:
    n = int(rate * ms / 1000)
    if n <= 0 or x.size < 2 * n:
        return x
    win = np.hanning(2 * n).astype(np.float32)
    x[:n] *= win[:n]
    x[-n:] *= win[n:]
    return x


def chime_wake() -> np.ndarray:
    """Miękki, dwutonowy dzwonek „budzenia” (Hej Nixi)."""
    a = _tone(880.0, 0.22, harmonics=(1.0, 0.35, 0.12), decay=4.0)
    b = _tone(1318.5, 0.30, harmonics=(1.0, 0.30, 0.10), decay=4.5)
    x = np.concatenate([a, b, np.zeros(int(0.03 * RATE), np.float32)])
    return _fade(x * 0.8)


def blip_confirm() -> np.ndarray:
    x = _tone(1200.0, 0.10, harmonics=(1.0, 0.3), decay=6.0)
    return _fade(x * 0.7)


def blip_cancel() -> np.ndarray:
    a = _tone(700.0, 0.09, harmonics=(1.0, 0.3), decay=6.0)
    b = _tone(450.0, 0.12, harmonics=(1.0, 0.3), decay=6.0)
    return _fade(np.concatenate([a, b]) * 0.7)


def err_sound() -> np.ndarray:
    a = _tone(220.0, 0.12, harmonics=(1.0, 0.5, 0.3), decay=4.0)
    b = _tone(196.0, 0.16, harmonics=(1.0, 0.5, 0.3), decay=4.0)
    return _fade(np.concatenate([a, b]) * 0.8)


def whoosh_end() -> np.ndarray:
    """Delikatne „pożegnanie” przy zamykaniu sesji."""
    n = int(0.45 * RATE)
    t = np.arange(n) / RATE
    freq = 600.0 * np.exp(-3.0 * t / 0.45)
    phase = np.cumsum(2 * np.pi * freq / RATE)
    x = np.sin(phase) * np.exp(-4.0 * t / 0.45)
    return _fade(x.astype(np.float32) * 0.8)


_CHIMES = {
    "wake": chime_wake,
    "confirm": blip_confirm,
    "cancel": blip_cancel,
    "error": err_sound,
    "end": whoosh_end,
}


def get_chime(kind: str) -> np.ndarray:
    fn = _CHIMES.get(kind)
    if fn is None:
        return np.zeros(int(0.1 * RATE), np.float32)
    return fn()
