"""Samotest Nixi — testy jednostkowe + test protokołu Live API na mockowym serwerze.

Użycie:
    python -m nixi.selftest                 # testy podstawowe
    python -m nixi.selftest --acoustics     # + test akustyczny wake worda (Piper+VOSK)
    python -m nixi.selftest --screenshot p.png  # render UI do pliku PNG
    python -m nixi.selftest --skip-ui       # środowiska headless bez Qt/OpenGL
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import logging
import os
import sys
import tempfile
import threading
import time
from pathlib import Path

import numpy as np

FAILURES: list[str] = []
SKIPPED: list[tuple[str, str]] = []


class SkipTest(Exception):
    """Test pominięty, bo środowisko nie udostępnia wymaganej funkcji."""


def check(name: str, fn) -> None:
    t0 = time.monotonic()
    try:
        fn()
        print(f"  ✓ {name}  ({time.monotonic() - t0:.2f}s)")
    except SkipTest as e:
        SKIPPED.append((name, str(e)))
        print(f"  ⊘ {name}: pominięto — {e}")
    except Exception as e:  # noqa: BLE001
        FAILURES.append(name)
        import traceback

        print(f"  ✗ {name}: {e}")
        traceback.print_exc()


# ------------------------------------------------------------- 1. stany
def test_state_machine() -> None:
    from nixi.state import (Event, State, StateError, advance, is_active)

    assert advance(State.BOOT, Event.RESET) == State.IDLE
    assert advance(State.IDLE, Event.WAKE_DETECTED) == State.WAKING
    assert advance(State.WAKING, Event.SESSION_STARTED) == State.LISTENING
    assert advance(State.LISTENING, Event.MODEL_TALKING) == State.SPEAKING
    assert advance(State.SPEAKING, Event.USER_TALKING) == State.LISTENING
    assert advance(State.LISTENING, Event.CONSENT_REQUESTED) == State.CONFIRMING
    assert advance(State.CONFIRMING, Event.CONSENT_GRANTED) == State.THINKING
    assert advance(State.THINKING, Event.ACTION_DONE) == State.LISTENING
    assert advance(State.LISTENING, Event.RESET) == State.IDLE
    assert advance(State.DEMO, Event.SESSION_ENDED) == State.IDLE
    assert is_active(State.LISTENING) and not is_active(State.IDLE)
    for bad in ((State.IDLE, Event.MODEL_TALKING), (State.SPEAKING, Event.WAKE_DETECTED),
                (State.IDLE, Event.CONSENT_GRANTED), (State.WAKING, Event.ACTION_STARTED)):
        try:
            advance(*bad)
            raise AssertionError(f"powinno rzucić StateError dla {bad}")
        except StateError:
            pass


# ------------------------------------------------------------ 2. konfiguracja
def test_config() -> None:
    from dataclasses import asdict
    from unittest.mock import patch

    from nixi import config as C

    s = C.AppSettings()
    merged = C._merge(asdict(s), {"safety": {"confirm_all": True}, "model": "test-model"})
    assert merged["safety"]["confirm_all"] is True and merged["model"] == "test-model"
    ok, _ = C.validate_api_key("AIzaSy" + "A" * 27)
    assert ok
    ok, msg = C.validate_api_key("AQ.Ab8RN6im" + "x" * 42)
    assert not ok, "token OAuth nie powinien przejść walidacji klucza"

    tmp = Path(tempfile.mkdtemp())
    with patch.object(C.paths, "settings_path", return_value=tmp / "settings.json"), \
         patch.object(C.paths, "settings_local_path", return_value=tmp / "settings.local.json"), \
         patch.dict(os.environ, {"GEMINI_API_KEY": ""}):
        assert C.resolve_api_key(C.AppSettings(api_key="")) == ("", "none")
        robust = C.settings_from_dict({
            "user_name": None,
            "wake": {"cooldown_s": "nie-liczba", "unknown_future_field": True},
            "safety": {"confirm_all": "tak"},
        })
        assert robust.user_name == ""
        assert robust.wake.cooldown_s == 2.5
        assert robust.safety.confirm_all is True
        robust.api_key = "AIza" + "A" * 30
        C.save_settings(robust)
        normal = json.loads((tmp / "settings.json").read_text(encoding="utf-8"))
        secret = json.loads((tmp / "settings.local.json").read_text(encoding="utf-8"))
        assert "api_key" not in normal and secret["api_key"] == robust.api_key
        assert C.load_settings().api_key == robust.api_key

    assert any("do widzenia" in p for p in C.GOODBYE_PATTERNS)
    assert any("tak" in p for p in C.CONSENT_PATTERNS)


# --------------------------------------------------------------- 3. pamięć
def test_memory() -> None:
    from nixi.core.memory import MemoryStore

    tmp = Path(tempfile.mkdtemp())
    m = MemoryStore(tmp / "m.db")
    m.add_memory("Użytkownik ma na imię Jan i uwielbia kawę.", "fact")
    m.add_memory("Ulubiony zespół użytkownika to Queen.", "preference")
    m.add_note("Zakupy", "mleko, chleb, masło")
    r = m.recall("jak ma na imię użytkownik", 3)
    assert any("Jan" in x["content"] for x in r), r
    r2 = m.recall("zespół muzyczny", 3)
    assert any("Queen" in x["content"] for x in r2), r2
    notes = m.read_notes()
    assert notes and notes[0]["title"] == "Zakupy"
    m.set_kv("user_name", "Jan")
    assert m.get_kv("user_name") == "Jan"
    m.log_conversation("s1", "user", "hej")
    assert m.recent_dialog(1)[0]["role"] == "user"
    stats = m.stats()
    assert stats["memories"] >= 2 and stats["notes"] >= 1
    m.close()


# ------------------------------------------------------------ 4. narzędzia
def test_tools() -> None:
    from nixi import config as C
    from nixi.tools import browser, notes_memory, screen_control, system_power
    from nixi.tools.registry import TOOLS, ToolContext

    names = {t.name for t in TOOLS}
    for required in (
        "search_web", "open_website", "open_app", "close_app", "set_volume", "take_screenshot",
        "move_mouse", "click_at", "scroll", "type_text", "press_keys", "foreground_app",
        "remember_memory", "recall_memory", "create_note", "read_notes", "system_info",
        "control_system", "end_session", "set_brightness",
    ):
        assert required in names, f"brak narzędzia: {required}"

    class MemStub:
        def add_memory(self, *a, **k):
            return 1

        def update_embedding(self, *a, **k):
            pass

        def recall(self, *a, **k):
            return []

    ctx = ToolContext(C.AppSettings(), MemStub())
    for t in TOOLS:
        p = t.parameters
        assert p.get("type") == "OBJECT", t.name
        assert isinstance(p.get("properties", {}), dict), t.name
        assert t.confirm in ("never", "risky"), t.name
        assert callable(t.execute), t.name

    # bezpieczne, cross-platformowe
    r = browser.open_website({"url": "javascript:alert(1)"}, ctx)
    assert r["status"] == "error"
    r = browser.search_web({"query": "pogoda kraków"}, ctx)
    assert r["status"] == "ok"
    r = notes_memory.recall_memory({"query": "imię"}, ctx)
    assert r["status"] == "ok"
    r = system_power.system_info({}, ctx)
    assert r["status"] == "ok"
    r = notes_memory.remember_memory({"content": "Test"}, ctx)
    assert r["status"] == "ok"
    # Windows-only na Linuxie → czytelny błąd, nie wyjątek
    if sys.platform != "win32":
        for fn, a in ((screen_control.click_at, {"x": 10, "y": 10}),
                      (screen_control.type_text, {"text": "x"}),
                      (screen_control.press_keys, {"combo": "ctrl+s"}),
                      (system_power.control_system, {"action": "lock"})):
            r = fn(a, ctx)
            assert r["status"] == "error", (fn.__name__, r)


# ------------------------------------------------------------------ 5. VAD
def test_vad() -> None:
    from nixi.audio.vad import EnergyVAD

    vad = EnergyVAD()
    silence = np.zeros(320, dtype=np.int16)
    tone = (np.sin(2 * np.pi * 440 * np.arange(320) / 16000) * 12000).astype(np.int16)
    for _ in range(60):
        vad.process(silence)
    assert vad.process(tone)[0] is True
    hang = [vad.process(silence)[0] for _ in range(25)]
    assert any(hang), "histereza VAD powinna podtrzymać mowę"


# ------------------------------------------------------------ 6. wake word
def test_wake_matching() -> None:
    from nixi.audio.wake import WakeWordDetector, normalize_text

    w = WakeWordDetector([r"\bhej\s+nixi\b", r"\bhej\s+niki\b"])
    assert w.match_text("Hej Nixi, otwórz notatnik")[0]
    assert w.match_text("hej niki")[0]
    assert w.match_text("HEJ  NIXI!")[0]
    assert w.match_text("no więc hej nixi proszę")[0]
    assert not w.match_text("pogoda dzisiaj jest ładna")[0]
    assert not w.match_text("hej, ale ładny dzień dzisiaj")[0]
    assert not w.match_text("nixie to fajne hasło")[0]
    assert normalize_text("Hej,  Nixi!!!") == "hej nixi"


# ------------------------------------------------------ 7. protokół Live API
async def _live_protocol_test() -> None:
    import websockets

    from nixi.core.live import GeminiLiveClient, LiveCallbacks

    received: list[str] = []

    async def handler(ws):
        # setup
        setup = json.loads(await asyncio.wait_for(ws.recv(), 10))["setup"]
        assert setup["model"].startswith("models/"), setup
        assert setup["responseModalities"] == ["AUDIO"], setup
        assert setup["systemInstruction"]["parts"][0]["text"], setup
        assert setup["tools"][0]["functionDeclarations"], setup
        assert "inputAudioTranscription" in setup and "outputAudioTranscription" in setup, setup
        await ws.send(json.dumps({"setupComplete": {}}))

        # audio wejściowe (16 kHz PCM)
        msg = json.loads(await asyncio.wait_for(ws.recv(), 10))
        assert "realtimeInput" in msg and "audio" in msg["realtimeInput"], msg
        assert msg["realtimeInput"]["audio"]["mimeType"] == "audio/pcm;rate=16000", msg
        assert base64.b64decode(msg["realtimeInput"]["audio"]["data"])

        # tekst
        while True:
            msg = json.loads(await asyncio.wait_for(ws.recv(), 10))
            if "realtimeInput" in msg and "text" in msg["realtimeInput"]:
                break

        # tool call → oczekiwana odpowiedź toolResponse
        await ws.send(json.dumps({"toolCall": {"functionCalls": [
            {"id": "t1", "name": "system_info", "args": {}}
        ]}}))
        resp = json.loads(await asyncio.wait_for(ws.recv(), 10))
        fr = resp["toolResponse"]["functionResponses"][0]
        assert fr["name"] == "system_info" and fr["id"] == "t1", fr
        assert fr["response"]["result"]["status"] == "ok", fr

        # audio wyjściowe + transkrypcje + turnComplete
        pcm = (np.sin(np.linspace(0, 100, 4800)) * 12000).astype(np.int16).tobytes()
        await ws.send(json.dumps({"serverContent": {
            "modelTurn": {"parts": [{"inlineData": {
                "data": base64.b64encode(pcm).decode(), "mimeType": "audio/pcm;rate=24000"}}]},
            "inputTranscription": {"text": "Hej Nixi"},
            "outputTranscription": {"text": "Cześć!"},
            "turnComplete": True,
        }}))

        # pożegnanie
        while True:
            msg = json.loads(await asyncio.wait_for(ws.recv(), 10))
            if "realtimeInput" in msg and "text" in msg["realtimeInput"]:
                break
        await ws.send(json.dumps({"serverContent": {
            "modelTurn": {"parts": [{"inlineData": {"data": base64.b64encode(pcm[:1000]).decode(),
                                                    "mimeType": "audio/pcm;rate=24000"}}]},
            "turnComplete": True,
        }}))
        await ws.close()

    async def _swallow(coro):
        try:
            await coro
        except Exception:  # noqa: BLE001
            pass

    async with websockets.serve(handler, "127.0.0.1", 0) as server:
        port = server.sockets[0].getsockname()[1]
        cb = LiveCallbacks(
            on_ready=lambda: received.append("ready"),
            on_audio=lambda data, mime: received.append(f"audio:{mime}:{len(data)}"),
            on_user_text=lambda t: received.append(f"user:{t}"),
            on_assistant_text=lambda t: received.append(f"asr:{t}"),
            on_tool_calls=lambda calls: asyncio.get_running_loop().create_task(
                _swallow(client.send_tool_response([{"name": "system_info", "id": "t1",
                                            "response": {"result": {"status": "ok", "output": "Linux"}}}]))),
            on_turn_complete=lambda: asyncio.get_running_loop().create_task(
                _swallow(client.send_text("do widzenia"))),
        )
        client = GeminiLiveClient(
            api_key="test-key", model="gemini-3.1-flash-live-preview",
            system_instruction="test", tools=[{"name": "system_info"}], callbacks=cb,
            ws_url=f"ws://127.0.0.1:{port}/ws?key={{key}}",
        )
        assert await client.connect(timeout_s=10)
        await client.send_audio(b"\x01\x02\x03\x04")
        await client.send_text("cześć")
        try:
            await asyncio.wait_for(client.wait(), 15)
        except asyncio.TimeoutError:
            raise AssertionError("mock nie zakończył sesji")

    assert "ready" in received, received
    assert any(r.startswith("audio:audio/pcm;rate=24000:") for r in received), received
    assert "user:Hej Nixi" in received, received
    assert "asr:Cześć!" in received, received


# ---------------------------------------- 8. sesja: zgoda + pożegnanie (mock)
async def _session_consent_test() -> None:
    import websockets

    from nixi import config as C
    from nixi.audio.player import OutputPlayer
    from nixi.core.memory import MemoryStore
    from nixi.core.session import ConversationSession, SessionCallbacks
    from nixi.tools.registry import ToolContext

    seen: dict[str, list] = {"tool_responses": [], "texts": []}
    ended = threading.Event()
    end_reason: list[str] = []
    consent_desc: list[str] = []

    async def handler(ws):
        msg = json.loads(await asyncio.wait_for(ws.recv(), 15))
        assert "setup" in msg
        await ws.send(json.dumps({"setupComplete": {}}))
        while True:
            msg = json.loads(await asyncio.wait_for(ws.recv(), 20))
            if "realtimeInput" in msg:
                ri = msg["realtimeInput"]
                if "text" in ri:
                    seen["texts"].append(ri["text"])
                    if "Przywitaj" in ri["text"]:
                        # wysyłamy ryzykowny tool call
                        await ws.send(json.dumps({"toolCall": {"functionCalls": [
                            {"id": "c1", "name": "click_at", "args": {"x": 100, "y": 200}}
                        ]}}))
                    if "Użytkownik kończy" in ri["text"] or "Kończymy" in ri["text"]:
                        pcm = (np.sin(np.linspace(0, 50, 1600)) * 9000).astype(np.int16).tobytes()
                        await ws.send(json.dumps({"serverContent": {
                            "modelTurn": {"parts": [{"inlineData": {"data": base64.b64encode(pcm).decode(),
                                                                    "mimeType": "audio/pcm;rate=24000"}}]},
                            "turnComplete": True}}))
                        await ws.close()
                        return
            elif "toolResponse" in msg:
                fr = msg["toolResponse"]["functionResponses"][0]
                seen["tool_responses"].append(fr)
                if fr["response"]["result"].get("status") == "requires_confirmation":
                    # użytkownik mówi „tak”
                    await ws.send(json.dumps({"serverContent": {"inputTranscription": {"text": "Tak, zrób to"}}}))
                elif fr["response"]["result"].get("status") == "error":
                    # po wykonaniu (Linux → error, ale próba wykonania nastąpiła)
                    await ws.send(json.dumps({"serverContent": {"inputTranscription": {"text": "Do widzenia"}}}))
                elif fr["response"]["result"].get("status") == "ok":
                    await ws.send(json.dumps({"serverContent": {"inputTranscription": {"text": "Do widzenia"}}}))

    async with websockets.serve(handler, "127.0.0.1", 0) as server:
        port = server.sockets[0].getsockname()[1]

        s = C.AppSettings()
        s.safety.confirm_risky = True
        s.vision.enabled = False
        s.session.silence_timeout_s = 300
        s.session.max_duration_s = 3600
        s.api_key = "test-key"

        memory = MemoryStore(Path(tempfile.mkdtemp()) / "m.db")
        ctx = ToolContext(s, memory)
        cb = SessionCallbacks(
            on_consent_required=lambda d: consent_desc.append(d),
            on_ended=lambda r: (end_reason.append(r), ended.set()),
        )
        session = ConversationSession(s, memory, OutputPlayer(), ctx, cb)
        # podmień URL klienta na lokalny mock
        import nixi.core.live as live_mod

        live_mod.WS_URL = f"ws://127.0.0.1:{port}/ws?key={{key}}"
        try:
            session.start()
            deadline = time.monotonic() + 40
            while not ended.is_set() and time.monotonic() < deadline:
                await asyncio.sleep(0.2)
            assert ended.is_set(), "sesja nie zakończyła się"
        finally:
            live_mod.WS_URL = ("wss://generativelanguage.googleapis.com/ws/"
                               "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key={key}")

    assert consent_desc, "nie zgłoszono wymaganej zgody"
    statuses = [fr["response"]["result"].get("status") for fr in seen["tool_responses"]]
    assert "requires_confirmation" in statuses, statuses
    # po zgodzie narzędzie wykonane (na Linuxie zwróci error, ale nie requires_confirmation)
    assert any(st in ("ok", "error") for st in statuses), statuses
    assert end_reason and end_reason[0] == "goodbye", end_reason


# ------------------------------------------------------ 9. synteza dźwięków
def test_synth() -> None:
    from nixi.audio import synth

    for kind in ("wake", "confirm", "cancel", "error", "end"):
        x = synth.get_chime(kind)
        assert x.ndim == 1 and x.size > 1000 and float(np.max(np.abs(x))) <= 1.0


# --------------------------------------------------- 10. test akustyczny
def test_acoustics() -> None:
    """Realny test: Piper (polski głos) → „Hej Nixi” → VOSK → detekcja."""
    from nixi.audio.wake import WakeWordDetector

    w = WakeWordDetector([r"\bhej\s+nixi\b", r"\bhej\s+niki\b", r"\bhej\s+nixy\b"])

    # Najpierw sprawdź opcjonalnego Pipera, aby bez niego nie pobierać
    # niepotrzebnie około 40 MB modelu VOSK.
    voice_path = None
    try:
        from piper.download_voices import download_voice, list_voices

        data_dir = Path(tempfile.mkdtemp())
        pl_voices = [v for v in list_voices() if v.lower().startswith("pl_")]
        candidates = ["pl_PL-darkman-medium", "pl_PL-gosia-medium"]
        chosen = next((c for c in candidates if c in pl_voices), None) or (pl_voices[0] if pl_voices else None)
        if chosen:
            download_voice(chosen, data_dir)
            voice_path = data_dir / f"{chosen}.onnx"
            if not voice_path.exists():
                voice_path = None
    except Exception as e:  # noqa: BLE001
        print(f"    (piper niedostępny: {e})")
    if not voice_path:
        print("    ⊘ pomijam test akustyczny — brak głosu Piper")
        return

    model_dir = w._resolve_model_dir()
    if not model_dir:
        raise AssertionError("brak modelu VOSK do testu akustycznego")

    from piper import PiperVoice

    voice = PiperVoice.load(voice_path)

    def synth(text: str) -> bytes:
        import io
        import wave

        buf = io.BytesIO()
        with wave.open(buf, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(16000)
            for chunk in voice.synthesize_stream_raw(text):
                wf.writeframes(chunk)
        return buf.getvalue()

    tmp = Path(tempfile.mkdtemp())
    pos1 = tmp / "p1.wav"
    pos1.write_bytes(synth("Hej Nixi."))
    pos2 = tmp / "p2.wav"
    pos2.write_bytes(synth("Hej Nixi, otwórz notatnik."))
    neg1 = tmp / "n1.wav"
    neg1.write_bytes(synth("Dzisiaj jest ładna pogoda."))
    neg2 = tmp / "n2.wav"
    neg2.write_bytes(synth("Cześć kolego, co słychać?"))

    w.start()
    try:
        time.sleep(0.5)
        r1 = w.detect_file(str(pos1))
        r2 = w.detect_file(str(pos2))
        n1 = w.detect_file(str(neg1))
        n2 = w.detect_file(str(neg2))
    finally:
        w.stop()

    print(f"    pozytywne: {r1!r} / {r2!r}; negatywne: {n1!r} / {n2!r}")
    assert r1 is not None, "nie wykryto „Hej Nixi” (1)"
    assert r2 is not None, "nie wykryto „Hej Nixi” (2)"
    assert n1 is None and n2 is None, "fałszywe wykrycie!"


# ------------------------------------------------------------ 11. QML smoke
def qml_smoke(screenshot_path: str | None = None) -> None:
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    # Software backend pozwala uruchomić test w kontenerze bez GPU. Nie usuwa
    # jednak systemowych bibliotek Qt (np. libGL); taki brak pomijamy tylko
    # wtedy, gdy użytkownik nie żądał konkretnego zrzutu.
    os.environ.setdefault("QT_QUICK_BACKEND", "software")
    try:
        from PySide6.QtCore import QCoreApplication, Qt
        from PySide6.QtGui import QGuiApplication, QImage
    except ImportError as exc:
        if not screenshot_path and "libGL" in str(exc):
            raise SkipTest("środowisko headless nie ma libGL — test UI uruchom na Windows lub z bibliotekami Qt") from exc
        raise
    from PySide6.QtQuickControls2 import QQuickStyle
    from PySide6.QtQuick import QQuickView

    QCoreApplication.setAttribute(Qt.AA_ShareOpenGLContexts)
    QQuickStyle.setStyle("Material")
    app = QGuiApplication(["-platform", "offscreen"])

    from nixi import paths
    from nixi.ui.controller import Controller

    controller = Controller()
    ui_dir = paths.ui_qml_path()
    view = QQuickView()
    view.setResizeMode(QQuickView.SizeRootObjectToView)
    view.engine().addImportPath(str(ui_dir))
    view.engine().rootContext().setContextProperty("bridge", controller)
    view.setSource("file://" + str(ui_dir / "main.qml"))
    assert view.status() == QQuickView.Ready, view.errors()
    view.resize(1280, 800)
    view.show()
    for _ in range(10):
        app.processEvents()
        time.sleep(0.05)
    if screenshot_path:
        img = view.grabWindow()
        assert not img.isNull()
        # zapis przez Pillow (pewny także bez pluginów imageformats Qt)
        from PIL import Image

        buf = img.convertToFormat(QImage.Format_RGB888)
        raw = bytearray()
        for y in range(buf.height()):
            raw += bytes(buf.constScanLine(y))[: buf.width() * 3]
        pil = Image.frombytes("RGB", (buf.width(), buf.height()), bytes(raw))
        pil.save(str(screenshot_path))
        print(f"    zrzut zapisany: {screenshot_path}")
    view.close()
    del view
    controller.shutdown()


# -------------------------------------------------------------- main
def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--acoustics", action="store_true")
    parser.add_argument("--screenshot", metavar="PLIK.png")
    parser.add_argument("--skip-ui", action="store_true", help="pomiń test QML (np. na serwerze bez Qt/GPU)")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.WARNING)

    print("Nixi self-test")
    check("maszyna stanów", test_state_machine)
    check("konfiguracja", test_config)
    check("pamięć długotrwała", test_memory)
    check("narzędzia i schematy", test_tools)
    check("VAD", test_vad)
    check("dopasowanie „Hej Nixi”", test_wake_matching)
    check("synteza dźwięków", test_synth)
    check("protokół Live API (mock)", lambda: asyncio.run(_live_protocol_test()))
    check("sesja: zgody + pożegnanie (mock)", lambda: asyncio.run(_session_consent_test()))
    if args.acoustics:
        check("test akustyczny wake word (Piper→VOSK)", test_acoustics)
    if args.skip_ui:
        SKIPPED.append(("interfejs QML (offscreen)", "flaga --skip-ui"))
        print("  ⊘ interfejs QML (offscreen): pominięto — flaga --skip-ui")
    else:
        check("interfejs QML (offscreen)", lambda: qml_smoke(args.screenshot))

    print()
    if SKIPPED:
        print("Pominięte testy:")
        for name, reason in SKIPPED:
            print(f"  - {name}: {reason}")
    if FAILURES:
        print(f"NIEPOWODZENIE: {len(FAILURES)} testów padło: {FAILURES}")
        return 1
    print("Wszystkie uruchomione testy przeszły. ✓")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
