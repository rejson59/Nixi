"""Mostek Qt: łączy audio, detektor „Hej Nixi”, sesje Gemini i interfejs QML.

Wątek główny to pętla Qt (UI). Wątki robocze: audio (mikrofon+VAD), wake word,
sesja Live API (asyncio), tool calling (executor). Komunikacja do UI wyłącznie
przez sygnały Qt (bezpieczne wątkowo).
"""
from __future__ import annotations

import json
import logging
import threading
import time
from dataclasses import asdict

import numpy as np
from PySide6.QtCore import QObject, QTimer, Signal, Property

from .. import config as config_mod
from .. import paths, version
from ..audio.player import OutputPlayer
from ..audio.vad import EnergyVAD
from ..audio.wake import WakeWordDetector
from ..core.demo import DemoSession
from ..core.memory import MemoryStore
from ..core.session import ConversationSession, SessionCallbacks
from ..state import Event, State, StateError, advance, is_active, label
from ..tools.registry import ToolContext


class Controller(QObject):
    stateChanged = Signal(str)       # nazwa stanu: idle, waking, listening, ...
    statusChanged = Signal(str)      # tekst statusu (pigułka)
    userText = Signal(str)           # transkrypcja użytkownika
    nixiText = Signal(str)           # transkrypcja Nixi (przyrosty)
    nixiFinal = Signal(str)          # zakończona wypowiedź Nixi
    consentRequired = Signal(str)    # opis akcji wymagającej zgody
    consentResolved = Signal(str)    # wykonano / anulowano
    actionLog = Signal(str)          # log akcji
    memoryAdded = Signal(str)        # toast „Zapamiętałam: …”
    audioLevel = Signal(float)       # poziom mikrofonu 0..1
    modelLevel = Signal(float)       # poziom głosu Nixi 0..1
    wakeTriggered = Signal()         # wykryto „Hej Nixi”
    errorNotice = Signal(str)
    demoMode = Signal(bool)
    voskStatus = Signal(str)
    settingsChanged = Signal()
    keyStatus = Signal(str)
    firstRun = Signal(bool)

    def __init__(self, settings=None, memory=None, player=None, logger: logging.Logger | None = None):
        super().__init__()
        self.log = logger or logging.getLogger("nixi.controller")
        self.settings = settings or config_mod.load_settings()
        self.memory = memory or MemoryStore(paths.db_path(), self.log)
        self.player = player or OutputPlayer(self.log)
        self.tool_ctx = ToolContext(self.settings, self.memory, self.log)

        self._state = State.BOOT
        self._vad = EnergyVAD()
        self._session: ConversationSession | None = None
        self._demo: DemoSession | None = None
        self._wake: WakeWordDetector | None = None
        self._stop_evt = threading.Event()
        self._audio_thread: threading.Thread | None = None
        self._level_tick = 0
        self._shutting_down = False

    # ---------------------------------------------------------------- start
    def start(self) -> None:
        api_key, _ = config_mod.resolve_api_key(self.settings)
        self.demoMode.emit(not bool(api_key))
        self._trans(Event.RESET)  # BOOT → IDLE
        self._start_wake()
        self._start_audio()

    def shutdown(self) -> None:
        self._shutting_down = True
        self._stop_evt.set()
        try:
            if self._session is not None:
                self._session.stop("shutdown", farewell=False)
            if self._demo is not None:
                self._demo.stop("shutdown")
            if self._wake is not None:
                self._wake.stop()
            if self._audio_thread is not None:
                self._audio_thread.join(timeout=2.0)
        finally:
            try:
                self.player.close()
            except Exception:  # noqa: BLE001
                pass
            try:
                self.memory.close()
            except Exception:  # noqa: BLE001
                pass

    # -------------------------------------------------------------- stany
    def _trans(self, event: Event) -> None:
        try:
            new = advance(self._state, event)
        except StateError as e:
            self.log.debug("Przejście odrzucone: %s", e)
            return
        if new != self._state:
            self._state = new
            self.stateChanged.emit(new.name.lower())
            self.statusChanged.emit(label(new))
        elif event == Event.USER_TALKING and self._state in (State.LISTENING, State.THINKING):
            self.statusChanged.emit("Słucham…")
        elif event == Event.MODEL_TALKING and self._state == State.SPEAKING:
            self.statusChanged.emit("Mówię…")

    @property
    def state_name(self) -> str:
        return self._state.name.lower()

    def isActive(self) -> bool:
        return is_active(self._state) and (self._session is not None or self._demo is not None)

    def dismissWelcome(self) -> None:
        try:
            self.memory.set_kv("first_run_done", "1")
        except Exception:  # noqa: BLE001
            pass

    def uiShader(self) -> bool:
        return bool(self.settings.ui.shader)

    uiShaderChanged = Signal()

    @Property(bool, notify=uiShaderChanged)
    def uiShaderEnabled(self) -> bool:
        return bool(self.settings.ui.shader)

    def _notify_ui_settings(self) -> None:
        self.uiShaderChanged.emit()

    # -------------------------------------------------------- wake + audio
    def _start_wake(self) -> None:
        if self._wake is not None:
            self._wake.stop()
        w = self.settings.wake
        self._wake = WakeWordDetector(
            w.phrases,
            model_dir=w.vosk_model_dir or None,
            allow_bare_nixi=w.allow_bare_nixi,
            cooldown_s=w.cooldown_s,
            on_wake=self._on_wake,
            on_status=lambda s: self.voskStatus.emit(s),
            on_model_ready=lambda d: self.voskStatus.emit("Nasłuch głosowy gotowy — powiedz „Hej Nixi”."),
            logger=self.log,
        )
        self._wake.start()

    def _start_audio(self) -> None:
        self._audio_thread = threading.Thread(target=self._audio_loop, name="nixi-audio", daemon=True)
        self._audio_thread.start()

    def _audio_loop(self) -> None:
        try:
            import sounddevice as sd
        except Exception:  # noqa: BLE001
            self.errorNotice.emit("Brak biblioteki audio (sounddevice).")
            return
        try:
            sd.query_devices(kind="input")
        except Exception as e:  # noqa: BLE001
            self.errorNotice.emit(f"Nie znaleziono mikrofonu: {e}")
            self.voskStatus.emit("Brak mikrofonu — wybudzanie głosowe niedostępne.")
            return
        try:
            with sd.InputStream(samplerate=16000, channels=1, dtype="int16",
                                blocksize=320, callback=self._mic_callback):
                while not self._stop_evt.is_set():
                    self._stop_evt.wait(0.25)
        except Exception as e:  # noqa: BLE001
            self.errorNotice.emit(f"Błąd mikrofonu: {e}")

    def _mic_callback(self, indata, frames, t_info, status) -> None:
        if self._shutting_down:
            return
        data = np.asarray(indata[:, 0], dtype=np.int16)
        is_speech, level = self._vad.process(data)

        self._level_tick += 1
        if self._level_tick % 3 == 0:
            self.audioLevel.emit(level)

        if self._state == State.IDLE and self.settings.wake.enabled and self._wake is not None:
            self._wake.feed(data, is_speech)
        elif is_active(self._state):
            if self._session is not None and self._session.active:
                self._session.enqueue_audio(data.tobytes())
            if is_speech and self._state == State.SPEAKING:
                self._trans(Event.USER_TALKING)  # barge-in

    # ------------------------------------------------------------- sesja
    def _on_wake(self, phrase: str) -> None:
        if self._state != State.IDLE:
            return
        self.log.info("Wybudzenie: %r", phrase)
        self.wakeTriggered.emit()
        self._trans(Event.WAKE_DETECTED)
        self.player.play_chime("wake")
        self._launch_session()

    def _launch_session(self) -> None:
        api_key, src = config_mod.resolve_api_key(self.settings)
        if not api_key:
            self.demoMode.emit(True)
            self._trans(Event.DEMO_STARTED)
            self._demo = DemoSession(self._session_callbacks(), self.player, self.log)
            self._demo.start()
            return
        self.settings.api_key = api_key
        self._trans(Event.SESSION_STARTED)
        self.tool_ctx.hooks = {
            "send_video": self._send_video_hook,
            "request_end": lambda reason: self._session is not None and self._session.stop(reason, farewell=True),
            "ui_note": lambda msg: self.memoryAdded.emit(msg),
        }
        self._session = ConversationSession(
            self.settings, self.memory, self.player, self.tool_ctx,
            self._session_callbacks(), self.log,
        )
        self._session.start()

    def _send_video_hook(self, jpeg: bytes) -> None:
        if self._session is not None:
            self._session.send_video_threadsafe(jpeg)

    def _session_callbacks(self) -> SessionCallbacks:
        cb = SessionCallbacks()
        cb.on_user_text = lambda t: (self.userText.emit(t), self._trans(Event.USER_TALKING))
        cb.on_assistant_text = lambda t: (self.nixiText.emit(t), self._trans(Event.MODEL_TALKING))
        cb.on_assistant_final = lambda t: self.nixiFinal.emit(t)
        cb.on_consent_required = lambda d: (self._trans(Event.CONSENT_REQUESTED), self.consentRequired.emit(d))
        cb.on_consent_resolved = self._on_consent_resolved
        cb.on_action = self._on_action
        cb.on_interrupted = lambda: (self.player.stop(), self._trans(Event.USER_TALKING))
        cb.on_model_level = lambda lvl: (self.modelLevel.emit(lvl), self._trans(Event.MODEL_TALKING))
        cb.on_started = lambda: self.statusChanged.emit("Połączono — słucham.")
        cb.on_ended = self._on_session_ended
        cb.on_error = lambda msg: self.errorNotice.emit(msg)
        cb.on_reconnected = lambda: self.statusChanged.emit("Połączenie odświeżone.")
        return cb

    def _on_action(self, name: str, done: bool, desc: str) -> None:
        self.actionLog.emit(desc)
        if done:
            self._trans(Event.ACTION_DONE)
        else:
            self._trans(Event.ACTION_STARTED)

    def _on_consent_resolved(self, what: str) -> None:
        if what == "wykonano":
            self.consentResolved.emit("Zgoda — wykonuję.")
            self._trans(Event.CONSENT_GRANTED)
            self._trans(Event.ACTION_DONE)
        else:
            self.consentResolved.emit("Anulowano — nic nie robię.")
            self._trans(Event.CONSENT_DENIED)

    def _on_session_ended(self, reason: str) -> None:
        self.log.info("Sesja zakończona: %s", reason)
        self._session = None
        self._demo = None
        if reason == "connect_error":
            self.errorNotice.emit("Nie udało się połączyć z Gemini. Sprawdź klucz API i połączenie internetowe.")
        elif reason == "goodbye":
            self.statusChanged.emit("Do usłyszenia! Wracam do czuwania.")
        elif reason == "silence":
            self.statusChanged.emit("Cisza — wracam do czuwania.")
        self._trans(Event.RESET)

    # ------------------------------------------------------- akcje z UI
    def toggleListen(self) -> None:
        """Skrót klawiszowy / klik: włącz nasłuch lub zakończ sesję."""
        if self._state == State.IDLE:
            self._on_wake("(skrót klawiszowy)")
        elif is_active(self._state):
            self.endSessionNow()

    def activateNow(self) -> None:
        if self._state == State.IDLE:
            self._on_wake("(przycisk)")

    def endSessionNow(self) -> None:
        if self._session is not None:
            self._session.stop("user_request", farewell=True)
        if self._demo is not None:
            self._demo.stop("user_request")
        QTimer.singleShot(6000, self._force_reset_check)

    def _force_reset_check(self) -> None:
        if self._session is None and self._demo is None:
            return
        self.log.warning("Wymuszam powrót do czuwania.")
        if self._session is not None:
            self._session.stop("forced", farewell=False)
        if self._demo is not None:
            self._demo.stop("forced")
        self._session = None
        self._demo = None
        self._trans(Event.RESET)

    # ---------------------------------------------------------- ustawienia
    def settingsJson(self) -> str:
        return json.dumps(asdict(self.settings), ensure_ascii=False)

    def saveSettings(self, json_str: str) -> str:
        try:
            data = json.loads(json_str or "{}")
        except json.JSONDecodeError as e:
            return f"Błąd JSON: {e}"
        try:
            s = config_mod.AppSettings()
            merged = config_mod._merge(asdict(s), data)
            self.settings = config_mod.AppSettings(**{k: merged[k] for k in merged if hasattr(s, k)})
            for section, cls in (
                ("wake", config_mod.WakeSettings), ("session", config_mod.SessionSettings),
                ("safety", config_mod.SafetySettings), ("vision", config_mod.VisionSettings),
                ("memory", config_mod.MemorySettings), ("ui", config_mod.UISettings),
                ("hotkeys", config_mod.HotkeySettings), ("system", config_mod.SystemSettings),
            ):
                if section in merged:
                    setattr(self.settings, section, cls(**merged[section]))
            config_mod.save_settings(self.settings)
            # ponowna konfiguracja detektora
            self._start_wake()
            self._apply_autostart()
            self._notify_ui_settings()
            self.settingsChanged.emit()
            return "Zapisano ustawienia."
        except Exception as e:  # noqa: BLE001
            self.log.exception("Błąd zapisu ustawień")
            return f"Błąd zapisu: {e}"

    def _apply_autostart(self) -> None:
        try:
            self.set_autostart(bool(self.settings.system.autostart))
        except Exception as e:  # noqa: BLE001
            self.log.warning("Nie udało się ustawić autostartu: %s", e)

    @staticmethod
    def set_autostart(enabled: bool) -> str:
        import sys

        if sys.platform != "win32":
            return "Autostart dostępny w wersji Windows (.exe)."
        import winreg

        key = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Run",
            0, winreg.KEY_SET_VALUE | winreg.KEY_QUERY_VALUE,
        )
        app_id = "Nixi"
        if enabled:
            exe = getattr(sys, "executable", None)
            if getattr(sys, "frozen", False) and exe:
                winreg.SetValueEx(key, app_id, 0, winreg.REG_SZ, f'"{exe}"')
            else:
                winreg.SetValueEx(key, app_id, 0, winreg.REG_SZ,
                                  f'"{sys.executable}" -m nixi')
            return "Nixi będzie startować z systemem."
        try:
            winreg.DeleteValue(key, app_id)
        except FileNotFoundError:
            pass
        return "Autostart wyłączony."

    def testApiKey(self, key: str) -> None:
        key = (key or "").strip()
        ok, msg = config_mod.validate_api_key(key)
        if not ok:
            self.keyStatus.emit(msg)
            return

        def _test() -> None:
            import urllib.request

            url = f"https://generativelanguage.googleapis.com/v1beta/models?key={key}"
            try:
                with urllib.request.urlopen(url, timeout=15) as r:
                    r.read()
                self.keyStatus.emit("Klucz poprawny — połączenie z Gemini działa. ✓")
            except urllib.error.HTTPError as e:
                if e.code in (400, 403):
                    self.keyStatus.emit("Klucz odrzucony przez Google (błędny lub wygasły).")
                else:
                    self.keyStatus.emit(f"Błąd połączenia z Google: HTTP {e.code}")
            except Exception as e:  # noqa: BLE001
                self.keyStatus.emit(f"Brak połączenia z Google: {e}")

        self.keyStatus.emit("Sprawdzam klucz…")
        threading.Thread(target=_test, name="nixi-keytest", daemon=True).start()

    def refreshVosk(self) -> None:
        self._start_wake()

    @property
    def version(self) -> str:
        return version.__version__
