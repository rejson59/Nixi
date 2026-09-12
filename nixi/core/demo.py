"""Tryb demo — działa bez klucza API (pokazuje interfejs i przepływ)."""
from __future__ import annotations

import logging
import threading
import time

import numpy as np


class DemoSession:
    """Symulowana sesja: dzwonek, „transkrypcja” i powrót do czuwania."""

    def __init__(self, callbacks, player, logger: logging.Logger | None = None):
        self.cb = callbacks
        self.player = player
        self.log = logger or logging.getLogger("nixi.demo")
        self._stop_evt = threading.Event()
        self._thread: threading.Thread | None = None

    @property
    def active(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop_evt.clear()
        self._thread = threading.Thread(target=self._run, name="nixi-demo", daemon=True)
        self._thread.start()

    def stop(self, reason: str = "demo_end") -> None:
        self._stop_evt.set()

    def _run(self) -> None:
        try:
            if self.cb.on_started:
                self.cb.on_started()
            time.sleep(0.4)
            if self.cb.on_user_text:
                self.cb.on_user_text("Hej Nixi!")
            if self.cb.on_assistant_text:
                self.cb.on_assistant_text(
                    "Cześć! Jestem Nixi. Działam teraz w trybie demo, bo nie ustawiono klucza "
                    "Gemini API. Otwórz ustawienia (⚙ w rogu ekranu) i wklej klucz z "
                    "aistudio.google.com/apikey — wtedy porozmawiamy naprawdę."
                )
            # animacja „mówienia”
            for i in range(24):
                if self._stop_evt.is_set():
                    break
                level = float(np.clip(abs(np.sin(i / 3.2)) * 0.85, 0.0, 1.0))
                if self.cb.on_model_level:
                    self.cb.on_model_level(level)
                time.sleep(0.12)
            if self.cb.on_assistant_final:
                self.cb.on_assistant_final(
                    "Cześć! Jestem Nixi. Działam teraz w trybie demo, bo nie ustawiono klucza Gemini API."
                )
        finally:
            self.player.play_chime("end")
            if self.cb.on_ended:
                self.cb.on_ended("demo_end")
