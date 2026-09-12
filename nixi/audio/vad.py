"""Detekcja aktywności głosowej (VAD) oparta o energię + adaptacyjny poziom szumu.

Zero zależności zewnętrznych — działa na każdym systemie i w PyInstaller.
"""
from __future__ import annotations

import numpy as np


class EnergyVAD:
    def __init__(
        self,
        rate: int = 16000,
        frame_ms: int = 20,
        energy_margin_db: float = 8.0,
        hangover_frames: int = 12,   # ile klatek po zaniku głosu trzymać "mowę"
        min_noise_db: float = -65.0,
    ):
        self.rate = rate
        self.frame_ms = frame_ms
        self.frame_len = rate * frame_ms // 1000
        self.margin_db = energy_margin_db
        self.hangover = hangover_frames
        self.min_noise_db = min_noise_db

        self._noise_db: float = -55.0
        self._hang_count: int = 0
        self._n_frames: int = 0

    def process(self, frame: np.ndarray) -> tuple[bool, float]:
        """Zwraca (czy_mowa_z_histerezą, poziom 0..1)."""
        x = np.asarray(frame, dtype=np.float32)
        if x.size == 0:
            return False, 0.0
        rms = float(np.sqrt(np.mean(x ** 2)) + 1e-9)
        db = 20.0 * np.log10(rms / 32768.0 + 1e-12)
        db = max(db, -90.0)

        speech_now = db > self._noise_db + self.margin_db

        # adaptacyjny szum: ucz się tylko na klatkach "cichych"
        if self._n_frames < 10:
            self._noise_db = 0.9 * self._noise_db + 0.1 * min(db, self._noise_db + 3.0)
        elif not speech_now and db < self._noise_db + 6.0:
            self._noise_db = 0.97 * self._noise_db + 0.03 * db
        self._noise_db = max(self._noise_db, self.min_noise_db)
        self._n_frames += 1

        if speech_now:
            self._hang_count = self.hangover
            out = True
        elif self._hang_count > 0:
            self._hang_count -= 1
            out = True
        else:
            out = False

        level = float(np.clip((db + 60.0) / 54.0, 0.0, 1.0))
        return out, level
