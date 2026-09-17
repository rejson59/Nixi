"""Odtwarzacz audio (sounddevice) z resamplingiem i kolejką wątkowo-bezpieczną.

Gdy nie ma urządzenia wyjściowego (np. CI) — działa w trybie cichym (no-op).
"""
from __future__ import annotations

import logging
import queue
import threading

import numpy as np

try:
    import sounddevice as sd
    _HAS_SD = True
except Exception:  # noqa: BLE001  # pragma: no cover
    sd = None
    _HAS_SD = False


def resample(x: np.ndarray, src_rate: int, dst_rate: int) -> np.ndarray:
    if src_rate == dst_rate or x.size == 0:
        return x
    n_out = max(1, round(x.size * dst_rate / src_rate))
    xp = np.linspace(0.0, 1.0, x.size, endpoint=False)
    xq = np.linspace(0.0, 1.0, n_out, endpoint=False)
    return np.interp(xq, xp, x).astype(np.float32)


class OutputPlayer:
    def __init__(self, logger: logging.Logger | None = None):
        self.log = logger or logging.getLogger("nixi.audio")
        self.available = False
        self._rate: int | None = None
        self._stream = None
        self._q: queue.Queue = queue.Queue()
        self._leftover = np.zeros(0, dtype=np.float32)
        self._lock = threading.Lock()
        self._playing = False
        if not _HAS_SD:
            return
        try:
            info = sd.query_devices(kind="output")
            self._rate = int(info["default_samplerate"])
        except Exception as e:  # noqa: BLE001
            self.log.warning("Brak urządzenia wyjściowego audio: %s", e)
            return
        try:
            # OutputStream (nie RawOutputStream) — callback dostaje tablicę numpy,
            # dzięki czemu zapis `outdata[:] = ...` jest poprawny.
            self._stream = sd.OutputStream(
                samplerate=self._rate, channels=1, dtype="float32",
                blocksize=1024, callback=self._cb,
            )
            self._stream.start()
            self.available = True
        except Exception as e:  # noqa: BLE001
            self.log.warning("Nie udało się otworzyć wyjścia audio: %s", e)
            self._stream = None

    def _cb(self, outdata, frames, time_info, status):
        out = np.zeros(frames, dtype=np.float32)
        pos = 0
        with self._lock:
            while pos < frames:
                if self._leftover.size == 0:
                    try:
                        samples, rate = self._q.get_nowait()
                    except queue.Empty:
                        break
                    samples = np.asarray(samples, dtype=np.float32)
                    if rate and rate != self._rate:
                        samples = resample(samples, rate, self._rate)
                    self._leftover = samples
                take = min(frames - pos, self._leftover.size)
                out[pos:pos + take] = self._leftover[:take]
                self._leftover = self._leftover[take:]
                pos += take
            self._playing = (self._q.qsize() > 0) or (self._leftover.size > 0)
        outdata[:] = out.reshape(-1, 1)

    @property
    def playing(self) -> bool:
        return self._playing

    def play(self, samples: np.ndarray, rate: int = 24000) -> bool:
        if not self.available or samples is None or np.asarray(samples).size == 0:
            return False
        try:
            self._q.put_nowait((np.asarray(samples, dtype=np.float32), rate))
            return True
        except queue.Full:
            return False

    def play_chime(self, kind: str) -> bool:
        from . import synth
        return self.play(synth.get_chime(kind), synth.RATE)

    def stop(self) -> None:
        """Natychmiastowe uciszenie (barge-in / koniec sesji)."""
        with self._lock:
            try:
                while True:
                    self._q.get_nowait()
            except queue.Empty:
                pass
            self._leftover = np.zeros(0, dtype=np.float32)
            self._playing = False

    def close(self) -> None:
        try:
            if self._stream is not None:
                self._stream.stop()
                self._stream.close()
        except Exception:  # noqa: BLE001
            pass
        self._stream = None
