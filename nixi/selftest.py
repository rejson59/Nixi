"""Samotest Nixi — testy jednostkowe + test protokołu Live API na mockowym serwerze.

Użycie:
    python -m nixi.selftest                 # testy podstawowe
    python -m nixi.selftest --acoustics     # + test akustyczny wake worda (Piper+VOSK)
    python -m nixi.selftest --screenshot p.png  # render UI do pliku PNG
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


def check(name: str, fn) -> None:
    t0 = time.monotonic()
    try:
        fn()
        print(f"  ✓ {name}  ({time.monotonic() - t0:.2f}s)")
    except Exception as e:  # noqa: BLE001
        FAILURES.append(name)
        import traceback

        print(f"  ✗ {name}: {e}")
        traceback.print_exc()


# ------------------------------------------------------------- 1. stany
def test_state_machine() -> None:
    from nixi.state import Event, State, StateError, advance, is_active

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

    from nixi import config as C

    s = C.AppSettings()
    merged = C._merge(asdict(s), {"safety": {"confirm_all": True}, "model": "test-model"})
    assert merged["safety"]["confirm_all"] is True and merged["model"] == "test-model"
    ok, _ = C.validate_api_key("AIzaSy" + "A" * 27)
    assert ok
    ok, _msg = C.validate_api_key("AQ.Ab8RN6im" + "x" * 42)
    assert not ok, "token OAuth nie powinien przejść walidacji klucza"
    assert C.resolve_api_key(C.AppSettings(api_key="")) == ("", "none")
    assert any("do widzenia" in p for p in C.GOODBYE_PATTERNS)
    assert any("tak" in p for p in C.CONSENT_PATTERNS)

    # from_dict: braki, nieznane klucze i stare pliki ustawień nie mogą wywalić startu
    s2 = C.from_dict({"model": "x", "wake": {"cooldown_s": 3.5, "nieistniejace_pole": 1},
                      "smieci": True})
    assert s2.model == "x" and s2.wake.cooldown_s == 3.5
    assert s2.session.silence_timeout_s == C.SessionSettings().silence_timeout_s
    assert isinstance(s2.hotkeys, C.HotkeySettings)
    assert C.from_dict({}) == C.AppSettings()
    # zapis → odczyt (round-trip po asdict)
    assert C.from_dict(asdict(s2)) == s2


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

    # Wyszukiwanie musi działać niezależnie od polskich znaków w zapytaniu.
    # (indeks FTS trzyma treść ASCII-składaną — zapytania też są składane)
    m.add_memory("Użytkownik mieszka w Łodzi i lubi łyżwy.", "fact")
    for query in ("zespół", "zespol", "ZESPOL"):
        hits = m.recall(query, 3)
        assert any("Queen" in x["content"] for x in hits), (query, hits)
    for query in ("łyżwy", "lyzwy"):
        hits = m.recall(query, 3)
        assert any("Łodzi" in x["content"] for x in hits), (query, hits)
    # zapytanie ze znakami specjalnymi FTS nie może wywalić wyszukiwania
    assert isinstance(m.recall('cudzysłów " AND (', 3), list)

    # usuwanie czyści też indeks
    mid = m.add_memory("Tymczasowe wspomnienie o ananasie.", "fact")
    assert any("ananas" in x["content"] for x in m.recall("ananasie", 5))
    m.forget(mid)
    assert not any("ananas" in x["content"] for x in m.recall("ananasie", 5))
    m.close()


def test_memory_migration() -> None:
    """Baza z poprzedniej wersji (wadliwy indeks FTS) musi się przenieść bez utraty danych."""
    import sqlite3

    from nixi.core.memory import MemoryStore

    db = Path(tempfile.mkdtemp()) / "old.db"
    legacy = sqlite3.connect(db)
    legacy.executescript(
        """
        CREATE TABLE memories(id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL,
          content TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'fact',
          meta TEXT NOT NULL DEFAULT '{}', embedding BLOB);
        CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL,
          title TEXT NOT NULL, content TEXT NOT NULL DEFAULT '');
        CREATE TABLE conversations(id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL,
          session_id TEXT, role TEXT NOT NULL, text TEXT NOT NULL);
        CREATE TABLE kv(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE VIRTUAL TABLE memories_fts USING fts5(content, category,
          content='memories', content_rowid='id', tokenize='unicode61');
        CREATE TRIGGER memories_ai AFTER INSERT ON memories BEGIN
          INSERT INTO memories_fts(rowid, content, category)
          VALUES (new.id, new.content, new.category);
        END;
        """
    )
    legacy.execute("INSERT INTO memories(ts, content, category, meta) VALUES(1, ?, 'preference', '{}')",
                   ("Ulubiony zespół użytkownika to Queen.",))
    legacy.execute("INSERT INTO kv(key, value) VALUES('user_name', 'Jan')")
    legacy.commit()
    legacy.close()

    m = MemoryStore(db)
    try:
        assert m.get_kv("user_name") == "Jan", "migracja zgubiła dane kv"
        assert m.stats()["memories"] == 1, "migracja zgubiła wspomnienia"
        hits = m.recall("zespol", 3)
        assert any("Queen" in x["content"] for x in hits), hits
        m.add_memory("Nowe wspomnienie o kawie.", "fact")
        assert any("kawie" in x["content"] for x in m.recall("kawie", 3))
    finally:
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
        except TimeoutError as e:
            raise AssertionError("mock nie zakończył sesji") from e

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
PIPER_PL_CANDIDATES = ("pl_PL-darkman-medium", "pl_PL-gosia-medium")


def _acoustics_required() -> bool:
    """Czy brak głosu/modelu ma wywalić test (CI), czy tylko go pominąć (lokalnie)?"""
    return os.environ.get("NIXI_REQUIRE_ACOUSTICS", "").strip().lower() in ("1", "true", "yes")


def _download_piper_voice(data_dir: Path, attempts: int = 3) -> Path | None:
    """Pobierz polski głos Piper. Zwraca ścieżkę .onnx albo None (brak sieci/pakietu)."""
    try:
        from piper.download_voices import download_voice
    except ImportError as e:
        print(f"    (piper niedostępny: {e})")
        return None
    last_err: Exception | None = None
    for name in PIPER_PL_CANDIDATES:
        for attempt in range(1, attempts + 1):
            try:
                # UWAGA: piper.download_voices.list_voices() tylko DRUKUJE listę i zwraca None,
                # więc nie da się po niej iterować — pobieramy znane nazwy głosów wprost.
                download_voice(name, data_dir)
                onnx = data_dir / f"{name}.onnx"
                if onnx.exists() and (data_dir / f"{name}.onnx.json").exists():
                    return onnx
                last_err = RuntimeError(f"niekompletne pliki głosu {name}")
            except Exception as e:  # noqa: BLE001  (sieć potrafi rzucić czymkolwiek)
                last_err = e
                if attempt < attempts:
                    time.sleep(2.0 * attempt)
        print(f"    (głos {name} niedostępny: {last_err})")
    print(f"    (nie udało się pobrać żadnego głosu Piper: {last_err})")
    return None


def _piper_wav_16k(voice, text: str, out_path: Path) -> None:
    """Zsyntetyzuj tekst i zapisz jako WAV 16 kHz mono (format wymagany przez VOSK)."""
    import wave

    chunks = list(voice.synthesize(text))
    if not chunks:
        raise AssertionError(f"Piper nie zsyntetyzował audio dla: {text!r}")
    src_rate = int(chunks[0].sample_rate)
    audio = np.concatenate([
        np.frombuffer(c.audio_int16_bytes, dtype=np.int16).astype(np.float32) for c in chunks
    ])
    if src_rate != 16000:
        n_out = max(1, round(audio.size * 16000 / src_rate))
        audio = np.interp(
            np.linspace(0.0, 1.0, n_out, endpoint=False),
            np.linspace(0.0, 1.0, audio.size, endpoint=False),
            audio,
        )
    pcm = np.clip(audio, -32768, 32767).astype(np.int16)
    with wave.open(str(out_path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(16000)
        wf.writeframes(pcm.tobytes())


def test_acoustics() -> None:
    """Realny test: Piper (polski głos) → „Hej Nixi” → VOSK → detekcja."""
    from nixi.audio.wake import WakeWordDetector

    w = WakeWordDetector([r"\bhej\s+nixi\b", r"\bhej\s+niki\b", r"\bhej\s+nixy\b"])
    model_dir = w._resolve_model_dir()
    if not model_dir:
        if _acoustics_required():
            raise AssertionError("brak modelu VOSK do testu akustycznego")
        print("    ⊘ pomijam test akustyczny — brak modelu VOSK (offline?)")
        return

    voice_path = _download_piper_voice(Path(tempfile.mkdtemp()))
    if not voice_path:
        if _acoustics_required():
            raise AssertionError("nie udało się pobrać głosu Piper do testu akustycznego")
        print("    ⊘ pomijam test akustyczny — brak głosu Piper")
        return

    from piper import PiperVoice

    voice = PiperVoice.load(voice_path)

    tmp = Path(tempfile.mkdtemp())
    cases = {
        "p1": ("Hej Nixi.", True),
        "p2": ("Hej Nixi, otwórz notatnik.", True),
        "n1": ("Dzisiaj jest ładna pogoda.", False),
        "n2": ("Cześć kolego, co słychać?", False),
    }
    files: dict[str, Path] = {}
    for key, (text, _expect) in cases.items():
        path = tmp / f"{key}.wav"
        _piper_wav_16k(voice, text, path)
        files[key] = path

    results = {key: w.detect_file(str(path)) for key, path in files.items()}
    print("    wyniki: " + ", ".join(f"{k}={v!r}" for k, v in results.items()))

    for key, (text, expect_hit) in cases.items():
        got = results[key]
        if expect_hit:
            assert got is not None, f"nie wykryto „Hej Nixi” w: {text!r}"
        else:
            assert got is None, f"fałszywe wykrycie w: {text!r} → {got!r}"


# ----------------------------------------------- 10a. spójność metadanych
def test_packaging_metadata() -> None:
    """Wersja w version_info.txt (metadane .exe) musi zgadzać się z nixi/version.py."""
    from nixi import version

    root = Path(__file__).resolve().parent.parent
    info = (root / "version_info.txt").read_text(encoding="utf-8")
    v = version.__version__
    assert f"u'FileVersion', u'{v}'" in info, f"FileVersion != {v} w version_info.txt"
    assert f"u'ProductVersion', u'{v}'" in info, f"ProductVersion != {v} w version_info.txt"
    parts = (*(int(x) for x in v.split(".")), 0)
    compact = info.replace(" ", "")
    expected = "(" + ",".join(str(x) for x in parts) + ")"
    assert f"filevers={expected}" in compact, f"filevers != {expected}"
    assert f"prodvers={expected}" in compact, f"prodvers != {expected}"

    # requirements-build.txt nie może wskrzesić pakietu niedostępnego na PyPI
    build_reqs = (root / "requirements-build.txt").read_text(encoding="utf-8")
    pkgs = [
        line.split("#", 1)[0].strip()
        for line in build_reqs.splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    assert not any(p.startswith("piper-phonemize") for p in pkgs), (
        "piper-phonemize nie istnieje na PyPI dla Pythona 3.12 — psuje instalację w CI"
    )
    assert any(p.startswith("piper-tts") for p in pkgs), "brak piper-tts (test akustyczny)"


# ------------------------------------------- 10b. mostek QML (sloty, resample)
def test_controller_slots() -> None:
    """Metody wołane z QML muszą być zarejestrowane jako sloty Qt, inaczej QML ich nie widzi."""
    from PySide6.QtCore import QObject  # noqa: F401  (inicjalizacja metaobiektów)

    from nixi.ui.controller import Controller

    meta = Controller.staticMetaObject
    registered = {
        meta.method(i).methodSignature().data().decode()
        for i in range(meta.methodOffset(), meta.methodCount())
    }
    for sig in ("isActive()", "dismissWelcome()", "toggleListen()", "endSessionNow()",
                "settingsJson()", "saveSettings(QString)", "testApiKey(QString)",
                "refreshVosk()"):
        assert sig in registered, f"brak slotu QML: {sig} (mam: {sorted(registered)})"
    assert meta.indexOfProperty("uiShaderEnabled") >= 0, "brak właściwości uiShaderEnabled"


def test_settings_roundtrip() -> None:
    """Zapis ustawień z UI: walidacja regexów i klucz API tylko w settings.local.json."""
    import json as _json

    from nixi import config as C
    from nixi import paths
    from nixi.core.memory import MemoryStore
    from nixi.ui.controller import Controller, _split_regexes

    good, bad = _split_regexes([r"\bhej\s+nixi\b", "[niedomknieta"])
    assert good == [r"\bhej\s+nixi\b"], good
    assert len(bad) == 1, bad

    tmp = Path(tempfile.mkdtemp())
    old_data_dir = paths.data_dir
    paths.data_dir = lambda: tmp  # type: ignore[assignment]
    try:
        base = C.AppSettings()
        base.wake.enabled = False
        ctrl = Controller(settings=base, memory=MemoryStore(tmp / "m.db"))
        try:
            # wake.enabled=False → test nie próbuje pobierać modelu VOSK (offline, szybko)
            payload = _json.dumps({"api_key": "AIzaSy" + "A" * 27, "user_name": "Jan",
                                   "wake": {"cooldown_s": 3.5, "enabled": False}})
            msg = ctrl.saveSettings(payload)
            assert "Zapisano" in msg, msg
            public = _json.loads((tmp / "settings.json").read_text(encoding="utf-8"))
            local = _json.loads((tmp / "settings.local.json").read_text(encoding="utf-8"))
            assert public["api_key"] == "", "klucz API nie może trafić do settings.json!"
            assert public["user_name"] == "Jan" and public["wake"]["cooldown_s"] == 3.5
            assert local["api_key"].startswith("AIzaSy")

            bad = ctrl.saveSettings(_json.dumps({"wake": {"phrases": ["[zly regex"], "enabled": False}}))
            assert "nieprawidłowe wyrażenie" in bad, bad
            assert "Błąd JSON" in ctrl.saveSettings("{nie-json}")
            assert _json.loads(ctrl.settingsJson())["user_name"] == "Jan"
        finally:
            ctrl.shutdown()
    finally:
        paths.data_dir = old_data_dir  # type: ignore[assignment]


def test_audio_resample() -> None:
    from nixi.audio.player import resample

    x = np.sin(np.linspace(0, 8 * np.pi, 2400, dtype=np.float32))
    up = resample(x, 24000, 48000)
    down = resample(x, 24000, 16000)
    assert up.size == 4800 and down.size == 1600
    assert up.dtype == np.float32 and down.dtype == np.float32
    assert resample(x, 16000, 16000) is x
    assert resample(np.zeros(0, dtype=np.float32), 16000, 24000).size == 0
    assert float(np.max(np.abs(down))) <= 1.01


# ----------------------------------------------------- 10c. statyczna analiza QML
def test_qml_lint() -> None:
    """qmllint wyłapuje błędy w QML bez uruchamiania GUI (działa też bez OpenGL)."""
    import shutil
    import subprocess

    ui_dir = Path(__file__).resolve().parent / "ui"
    files = sorted(str(p) for p in ui_dir.glob("*.qml"))
    assert files, f"brak plików QML w {ui_dir}"

    exe = shutil.which("pyside6-qmllint")
    if not exe:
        candidate = Path(sys.executable).parent / "pyside6-qmllint"
        exe = str(candidate) if candidate.exists() else None
    if not exe:
        print("    ⊘ pomijam qmllint — brak narzędzia w środowisku")
        return

    proc = subprocess.run([exe, *files], capture_output=True, text=True, check=False)
    output = (proc.stdout or "") + (proc.stderr or "")
    errors = [ln for ln in output.splitlines() if ln.startswith("Error:")]
    # Ostrzeżenia „unqualified" (m.in. dostęp do kontekstowego `bridge`) są tu normalne.
    serious = [
        ln for ln in output.splitlines()
        if ln.startswith("Warning:") and "[unqualified]" not in ln
    ]
    assert not errors, "qmllint zgłosił błędy:\n" + "\n".join(errors)
    assert not serious, "qmllint zgłosił ostrzeżenia:\n" + "\n".join(serious)


# ------------------------------------------------------------ 11. QML smoke
def qml_smoke(screenshot_path: str | None = None) -> None:
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    from PySide6.QtCore import QCoreApplication, Qt, QUrl

    try:
        from PySide6.QtGui import QGuiApplication, QImage
    except ImportError as e:
        # Na Windows (docelowa platforma) biblioteki Qt są zawsze w kole PySide6;
        # na gołym Linuksie CI może brakować libGL — wtedy pomijamy zamiast wywalać build.
        if sys.platform == "win32":
            raise
        print(f"    ⊘ pomijam test QML — brak bibliotek systemowych Qt ({e})")
        return
    from PySide6.QtQuick import QQuickView
    from PySide6.QtQuickControls2 import QQuickStyle

    QCoreApplication.setAttribute(Qt.AA_ShareOpenGLContexts)
    QQuickStyle.setStyle("Material")
    app = QGuiApplication.instance() or QGuiApplication(["-platform", "offscreen"])

    from nixi import paths
    from nixi.ui.controller import Controller

    controller = Controller()
    ui_dir = paths.ui_qml_path()
    view = QQuickView()
    view.setResizeMode(QQuickView.SizeRootObjectToView)
    view.engine().addImportPath(str(ui_dir))
    view.engine().rootContext().setContextProperty("bridge", controller)
    # QUrl.fromLocalFile — "file://" + ścieżka Windows (C:\...) daje nieprawidłowy URL
    view.setSource(QUrl.fromLocalFile(str(ui_dir / "main.qml")))
    assert view.status() == QQuickView.Ready, [e.toString() for e in view.errors()]
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
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.WARNING)

    print("Nixi self-test")
    check("maszyna stanów", test_state_machine)
    check("konfiguracja", test_config)
    check("pamięć długotrwała", test_memory)
    check("migracja bazy pamięci", test_memory_migration)
    check("narzędzia i schematy", test_tools)
    check("VAD", test_vad)
    check("dopasowanie „Hej Nixi”", test_wake_matching)
    check("synteza dźwięków", test_synth)
    check("protokół Live API (mock)", lambda: asyncio.run(_live_protocol_test()))
    check("sesja: zgody + pożegnanie (mock)", lambda: asyncio.run(_session_consent_test()))
    check("metadane pakietu", test_packaging_metadata)
    check("mostek QML (sloty)", test_controller_slots)
    check("zapis ustawień (UI)", test_settings_roundtrip)
    check("resampling audio", test_audio_resample)
    check("statyczna analiza QML (qmllint)", test_qml_lint)
    if args.acoustics:
        check("test akustyczny wake word (Piper→VOSK)", test_acoustics)
    check("interfejs QML (offscreen)", lambda: qml_smoke(args.screenshot))

    print()
    if FAILURES:
        print(f"NIEPOWODZENIE: {len(FAILURES)} testów padło: {FAILURES}")
        return 1
    print("Wszystkie testy przeszły. ✓")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
