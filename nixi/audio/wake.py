"""Detektor słowa wybudzającego „Hej Nixi”.

Działa lokalnie (offline) na modelu VOSK (polski, small) — pełne rozpoznawanie
mowy strumieniowej + dopasowanie fraz. Model pobierany automatycznie przy
pierwszym uruchomieniu (mirror główny + zapasowy na GitHubie).

Całość działa w osobnym wątku; klatki audio są podawane tylko wtedy, gdy VAD
wykryje mowę (oszczędność CPU w tle).
"""
from __future__ import annotations

import logging
import os
import queue
import re
import threading
import time
from pathlib import Path

import numpy as np

from .. import paths

VOSK_MODEL_NAME = "vosk-model-small-pl-0.22"
VOSK_DOWNLOAD_URLS = [
    "https://alphacephei.com/vosk/models/{name}.zip",
    "https://github.com/kercre123/vosk-models/raw/main/{name}.zip",
]

MAX_UTTERANCE_FRAMES = 300  # 6 s przy 20 ms
MAX_SILENCE_FRAMES = 25     # 0.5 s ciszy kończy wypowiedź
QUEUE_MAX = 4000


def normalize_text(text: str) -> str:
    text = (text or "").lower()
    text = re.sub(r"[^\w\sąćęłńóśźż]", " ", text)
    return re.sub(r"\s+", " ", text).strip()


class WakeWordDetector:
    def __init__(
        self,
        phrases: list[str],
        *,
        model_dir: str | None = None,
        allow_bare_nixi: bool = False,
        cooldown_s: float = 2.5,
        on_wake=None,
        on_status=None,
        on_model_ready=None,
        logger: logging.Logger | None = None,
    ):
        self.log = logger or logging.getLogger("nixi.wake")
        self.phrases = phrases or []
        self._compiled = [re.compile(p, re.IGNORECASE) for p in self.phrases]
        self._bare = allow_bare_nixi
        self._bare_res = [re.compile(r"\bnixi\b", re.IGNORECASE), re.compile(r"\bniki\b", re.IGNORECASE)]
        self._cooldown = cooldown_s
        self._on_wake = on_wake
        self._on_status = on_status
        self._on_model_ready = on_model_ready
        self._model_dir = model_dir
        self._q: queue.Queue = queue.Queue(maxsize=QUEUE_MAX)
        self._thread: threading.Thread | None = None
        self._running = False
        self._model = None
        self._ready = False
        self._last_trigger = 0.0
        self._utterance: list[np.ndarray] = []
        self._silence_run = 0
        self._rec = None

    # ------------------------------------------------------------------ API
    def start(self) -> None:
        if self._thread is not None:
            return
        self._running = True
        self._thread = threading.Thread(target=self._run, name="nixi-wake", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._running = False
        if self._thread is not None:
            self._thread.join(timeout=2.0)
            self._thread = None

    @property
    def ready(self) -> bool:
        return self._ready

    def feed(self, frames: np.ndarray, is_speech: bool) -> None:
        """Podaj klatkę int16 mono 16 kHz (20 ms). Wywoływane z wątku audio."""
        if not self._running:
            return
        try:
            self._q.put_nowait((np.asarray(frames, dtype=np.int16).copy(), bool(is_speech)))
        except queue.Full:
            pass

    # ---------------------------------------------------------- dopasowanie
    def match_text(self, text: str) -> tuple[bool, str]:
        """Sprawdź, czy transkrypcja zawiera frazę wybudzającą. (Do testów też.)"""
        t = normalize_text(text)
        if not t:
            return False, ""
        for rx in self._compiled:
            if rx.search(t):
                return True, t
        if self._bare:
            for rx in self._bare_res:
                if rx.search(t):
                    return True, t
        return False, ""

    # ---------------------------------------------------------------- wątek
    def _resolve_model_dir(self) -> str | None:
        if self._model_dir and Path(self._model_dir).is_dir():
            return str(Path(self._model_dir))
        # jawne wskazanie modelu (np. cache CI) przez zmienną środowiskowa
        env_dir = os.environ.get("NIXI_VOSK_MODEL_DIR", "").strip()
        if env_dir and (Path(env_dir) / "am").is_dir():
            return str(Path(env_dir))
        model_dir = paths.vosk_model_path(VOSK_MODEL_NAME)
        if (model_dir / "am").is_dir():
            return str(model_dir)
        # spróbuj też starszej lokalizacji (cache)
        alt = paths.cache_dir() / VOSK_MODEL_NAME
        if (alt / "am").is_dir():
            return str(alt)
        return self._download_model()

    def _download_model(self, attempts_per_url: int = 2) -> str | None:
        """Pobierz i rozpakuj model VOSK. Każdy mirror próbowany kilka razy."""
        import zipfile

        import requests

        target = paths.vosk_model_path(VOSK_MODEL_NAME)
        zip_path = target.parent / f"{target.name}.zip"
        tmp = target.parent / f"{target.name}.zip.part"
        errors = []
        for url in VOSK_DOWNLOAD_URLS:
            full = url.format(name=VOSK_MODEL_NAME)
            for attempt in range(1, attempts_per_url + 1):
                if not self._running and self._thread is not None:
                    return None
                try:
                    if self._on_status:
                        self._on_status("Pobieram model rozpoznawania mowy (ok. 40 MB)…")
                    self.log.info("Pobieranie modelu VOSK: %s (próba %d)", full, attempt)
                    with requests.get(full, stream=True, timeout=(15, 120)) as r:
                        r.raise_for_status()
                        total = int(r.headers.get("Content-Length") or 0)
                        done = 0
                        with open(tmp, "wb") as f:
                            for chunk in r.iter_content(chunk_size=1 << 16):
                                f.write(chunk)
                                done += len(chunk)
                                if total and self._on_status:
                                    self._on_status(f"Pobieram model mowy… {100 * done // total}%")
                    if tmp.stat().st_size < 1_000_000:
                        raise OSError(f"pobrany plik jest za mały ({tmp.stat().st_size} B)")
                    tmp.replace(zip_path)
                    with zipfile.ZipFile(zip_path) as z:
                        if z.testzip() is not None:
                            raise zipfile.BadZipFile("uszkodzone archiwum modelu")
                        z.extractall(paths.models_dir())
                    zip_path.unlink(missing_ok=True)
                    if (target / "am").is_dir():
                        self.log.info("Model VOSK gotowy: %s", target)
                        return str(target)
                    raise OSError("archiwum nie zawiera katalogu modelu")
                except Exception as e:  # noqa: BLE001  (sieć/IO — dowolny wyjątek)
                    errors.append(f"{full} (próba {attempt}): {e}")
                    self.log.warning("Pobranie modelu nie powiodło się: %s", e)
                    tmp.unlink(missing_ok=True)
                    zip_path.unlink(missing_ok=True)
                    if attempt < attempts_per_url:
                        time.sleep(2.0 * attempt)
        if self._on_status:
            self._on_status("Nie udało się pobrać modelu mowy — wybudzanie głosowe niedostępne.")
        self.log.error("Nie udało się pobrać modelu VOSK: %s", "; ".join(errors))
        return None

    def _run(self) -> None:
        try:
            model_dir = self._resolve_model_dir()
            if model_dir:
                import vosk

                self._model = vosk.Model(model_dir)
                self._rec = vosk.KaldiRecognizer(self._model, 16000)
                self._ready = True
                if self._on_model_ready:
                    self._on_model_ready(model_dir)
            else:
                if self._on_status:
                    self._on_status("Wybudzanie głosowe wyłączone (brak modelu). Skrót klawiszowy nadal działa.")
        except Exception as e:
            self.log.exception("Nie udało się załadować modelu VOSK")
            if self._on_status:
                self._on_status(f"Błąd modelu mowy: {e}")
            return

        while self._running:
            try:
                frames, is_speech = self._q.get(timeout=0.3)
            except queue.Empty:
                continue
            if not self._ready:
                continue
            if is_speech:
                if not self._utterance:
                    self._rec = self._new_recognizer()
                self._utterance.append(frames)
                self._silence_run = 0
                if len(self._utterance) >= MAX_UTTERANCE_FRAMES:
                    self._decode_utterance()
            elif self._utterance:
                self._silence_run += 1
                if self._silence_run >= MAX_SILENCE_FRAMES:
                    self._decode_utterance()

    def _new_recognizer(self):
        import vosk

        return vosk.KaldiRecognizer(self._model, 16000)

    def _decode_utterance(self) -> None:
        if not self._utterance or self._rec is None:
            self._utterance = []
            return
        data = b"".join(f.tobytes() for f in self._utterance)
        self._utterance = []
        try:
            self._rec.AcceptWaveform(data)
            result = self._rec.FinalResult()
            import json

            text = json.loads(result).get("text", "")
            hit, matched = self.match_text(text)
            if hit and self._cooldown_ok():
                self.log.info("WAKE WORD: %r", matched)
                self._last_trigger = time.monotonic()
                if self._on_wake:
                    self._on_wake(matched)
        except Exception as e:  # noqa: BLE001
            self.log.warning("Błąd dekodowania wypowiedzi: %s", e)

    def _cooldown_ok(self) -> bool:
        return (time.monotonic() - self._last_trigger) >= self._cooldown

    # --------------------------------------------------------------- testy
    def detect_file(self, wav_path: str, timeout_s: float = 15.0) -> str | None:
        """Przepuść plik WAV (16 kHz mono PCM) przez detektor — zwróć frazę lub None.

        Używa własnego recognizera (niezależnego od wątku nasłuchu) — bezpieczne dla testów.
        """
        import json
        import wave

        with wave.open(wav_path, "rb") as w:
            assert w.getframerate() == 16000, f"WAV musi być 16 kHz, jest {w.getframerate()}"
            assert w.getnchannels() == 1
            data = w.readframes(w.getnframes())

        model_dir = self._resolve_model_dir()
        if not model_dir:
            raise RuntimeError("brak modelu VOSK")
        import vosk

        model = vosk.Model(model_dir)
        rec = vosk.KaldiRecognizer(model, 16000)
        rec.AcceptWaveform(data)
        result = json.loads(rec.FinalResult())
        text = result.get("text", "")
        hit, matched = self.match_text(text)
        return matched if hit else None
