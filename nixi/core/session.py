"""Orkiestrator sesji rozmowy Nixi ↔ Gemini Live API.

Odpowiada za:
  - otwarcie WebSocket dopiero po słowie wybudzającym (nigdy wcześniej!),
  - strumieniowanie mikrofonu do modelu,
  - obsługę tool calli (w osobnym wątku, z limitem czasu),
  - politykę potwierdzeń (bez zgody Nixi niczego niebezpiecznego nie zrobi),
  - wykrywanie pożegnania / „przestań słuchać" i samoczynne zamykanie WS,
  - zrzuty ekranu (wizja),
  - watchdoga ciszy i limit czasu sesji.
"""
from __future__ import annotations

import asyncio
import json
import logging
import queue
import re
import threading
import time
from dataclasses import dataclass
from typing import Callable

import numpy as np

from .. import config as config_mod
from . import instructions
from .live import GeminiLiveClient, LiveCallbacks

_AUDIO_RATE_RE = re.compile(r"rate=(\d+)")


@dataclass
class SessionCallbacks:
    on_user_text: Callable[[str], None] = None
    on_assistant_text: Callable[[str], None] = None          # przyrosty
    on_assistant_final: Callable[[str], None] = None         # po turn_complete
    on_consent_required: Callable[[str], None] = None        # opis akcji
    on_consent_resolved: Callable[[str], None] = None        # "wykonano"/"anulowano"
    on_action: Callable[[str, bool, str], None] = None       # (nazwa, ok, opis)
    on_interrupted: Callable[[], None] = None
    on_model_level: Callable[[float], None] = None
    on_started: Callable[[], None] = None
    on_ended: Callable[[str], None] = None                   # powód zakończenia
    on_error: Callable[[str], None] = None
    on_reconnected: Callable[[], None] = None


def describe_action(name: str, args: dict) -> str:
    a = args or {}
    if name == "click_at":
        return f"kliknięcie w punkcie ({a.get('x')}, {a.get('y')})"
    if name == "move_mouse":
        return f"przesunięcie kursora do ({a.get('x')}, {a.get('y')})"
    if name == "type_text":
        return f"wpisanie tekstu: „{str(a.get('text', ''))[:60]}”"
    if name == "press_keys":
        return f"wciśnięcie klawiszy: {a.get('combo')}"
    if name == "scroll":
        return f"przewinięcie o {a.get('amount')}"
    if name == "close_app":
        return f"zamknięcie aplikacji: {a.get('name')}"
    if name == "control_system":
        return f"akcja systemowa: {a.get('action')}"
    if name == "open_app":
        return f"otwarcie aplikacji: {a.get('name')}"
    if name == "search_web":
        return f"wyszukanie: „{str(a.get('query', ''))[:60]}”"
    if name == "open_website":
        return f"otwarcie strony: {a.get('url')}"
    if name == "set_volume":
        return f"zmiana głośności: {a.get('level')}"
    if name == "remember_memory":
        return f"zapisanie wspomnienia: „{str(a.get('content', ''))[:60]}”"
    return name


class ConversationSession:
    def __init__(
        self,
        settings: config_mod.AppSettings,
        memory,
        player,
        tools_ctx,
        callbacks: SessionCallbacks,
        logger: logging.Logger | None = None,
    ):
        self.settings = settings
        self.memory = memory
        self.player = player
        self.tools_ctx = tools_ctx
        self.cb = callbacks or SessionCallbacks()
        self.log = logger or logging.getLogger("nixi.session")

        self._audio_q: queue.Queue = queue.Queue(maxsize=2000)
        self._end_evt = threading.Event()
        self._thread: threading.Thread | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._client: GeminiLiveClient | None = None
        self._pending: tuple | None = None   # (tool, args, call_id, name)
        self._farewell_sent = False
        self._last_activity = 0.0
        self._started_at = 0.0
        self._reconnected_once = False
        self._assistant_turn = ""
        self._end_reason = "unknown"
        self._model_speaking = False
        self._vision_frames = 0

    # ------------------------------------------------------------------- API
    @property
    def active(self) -> bool:
        return self._thread is not None and self._thread.is_alive() and not self._end_evt.is_set()

    def start(self) -> None:
        if self._thread is not None:
            return
        self._end_evt.clear()
        self._farewell_sent = False
        self._end_reason = "unknown"
        self._thread = threading.Thread(target=self._thread_main, name="nixi-session", daemon=True)
        self._thread.start()

    def enqueue_audio(self, frames: bytes) -> None:
        """PCM int16 mono 16 kHz z mikrofonu (wątek audio)."""
        if not self._end_evt.is_set():
            try:
                self._audio_q.put_nowait(frames)
            except queue.Full:
                pass

    def stop(self, reason: str = "user_request", farewell: bool = False) -> None:
        """Zatrzymaj sesję z dowolnego wątku. Zamyka WS i wraca do czuwania."""
        self._end_reason = reason
        self._end_evt.set()
        loop = self._loop
        if loop is not None and not loop.is_closed():
            try:
                future = asyncio.run_coroutine_threadsafe(self._finalize(farewell=farewell), loop)
                # konsumuj ewentualne wyjątki (np. zamknięty już socket)
                future.add_done_callback(lambda f: f.exception() if not f.cancelled() else None)
            except Exception:  # noqa: BLE001
                pass

    def request_end(self, reason: str = "user_request", farewell: bool = True) -> None:
        """Wywoływane też przez narzędzie end_session (z wątku asyncio)."""
        self.stop(reason, farewell=farewell)

    # ------------------------------------------------------------ wątek główny
    def _thread_main(self) -> None:
        asyncio.run(self._run())

    async def _run(self) -> None:
        self._loop = asyncio.get_running_loop()
        self._started_at = time.monotonic()
        self._last_activity = time.monotonic()
        if not await self._connect(retries=3):
            self._end_reason = "connect_error"
            self._notify_ended()
            return

        # przywitanie z kontekstem i pamięcią
        intro = self._build_intro()
        if self._client is not None:
            try:
                await self._client.send_text(intro)
            except Exception:  # noqa: BLE001
                pass
        if self.cb.on_started:
            self.cb.on_started()

        tasks = [
            asyncio.create_task(self._feed_audio_loop()),
            asyncio.create_task(self._watchdog_loop()),
            asyncio.create_task(self._duration_guard()),
            asyncio.create_task(self._vision_loop()),
        ]
        try:
            if self._client is not None:
                await self._client.wait()   # do zamknięcia WS
        except Exception as e:  # noqa: BLE001
            self.log.warning("Sesja zakończona wyjątkiem: %s", e)
        finally:
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            self._end_evt.set()
            self._notify_ended()

    def _notify_ended(self) -> None:
        try:
            if self.cb.on_ended:
                self.cb.on_ended(self._end_reason)
        finally:
            self.player.stop()

    async def _connect(self, retries: int = 3) -> bool:
        for attempt in range(retries):
            if self._end_evt.is_set():
                return False
            client = GeminiLiveClient(
                api_key=self.settings.api_key,
                model=self.settings.model,
                system_instruction=self._build_system_prompt(),
                tools=self.tools_ctx.function_declarations(),
                callbacks=self._make_callbacks(),
                logger=self.log,
            )
            ok = await client.connect()
            if ok:
                self._client = client
                return True
            if attempt < retries - 1:
                self.log.warning("Ponowna próba połączenia (%d/%d)…", attempt + 2, retries)
                await asyncio.sleep(1.5 * (attempt + 1))
        if self.cb.on_error:
            self.cb.on_error("Nie udało się połączyć z Gemini. Sprawdź klucz API i internet.")
        return False

    def _build_system_prompt(self) -> str:
        s = self.settings
        user_name = s.user_name or (self.memory.get_kv("user_name") or "")
        mems = self.memory.recall("", 6) if self.settings.memory.enabled else []
        dialog = self.memory.recent_dialog(4) if self.settings.memory.enabled else []
        context = instructions._context_block(user_name, mems, dialog)
        return instructions.SYSTEM_TEMPLATE.format(context=context, version="0.1.0")

    def _build_intro(self) -> str:
        user_name = self.settings.user_name or (self.memory.get_kv("user_name") or "")
        mems = self.memory.recent(5) if self.settings.memory.enabled else []
        dialog = self.memory.recent_dialog(4) if self.settings.memory.enabled else []
        context = instructions._context_block(user_name, mems, dialog)
        return (
            f"Zaczynamy rozmowę z użytkownikiem. Oto kontekst:\n{context}\n\n"
            "Przywitaj się z użytkownikiem naturalnie i krótko (jedno zdanie). "
            "Jeśli widzisz na ekranie coś istotnego, możesz to skomentować."
        )

    # ------------------------------------------------------- callbacks klienta
    def _make_callbacks(self) -> LiveCallbacks:
        return LiveCallbacks(
            on_ready=lambda: None,
            on_audio=self._on_audio,
            on_user_text=self._on_user_text,
            on_assistant_text=self._on_assistant_text,
            on_tool_calls=self._on_tool_calls,
            on_interrupted=self._on_interrupted,
            on_turn_complete=self._on_turn_complete,
            on_goaway=self._on_goaway,
            on_closed=lambda code, reason: None,
            on_error=lambda msg: self.cb.on_error(msg) if self.cb.on_error else None,
        )

    def _on_audio(self, pcm: bytes, mime: str) -> None:
        self._last_activity = time.monotonic()
        self._model_speaking = True
        m = _AUDIO_RATE_RE.search(mime or "")
        rate = int(m.group(1)) if m else 24000
        samples = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
        self.player.play(samples, rate)
        if self.cb.on_model_level and samples.size:
            level = float(np.clip(np.mean(np.abs(samples[:2048])) * 6.0, 0.0, 1.0))
            self.cb.on_model_level(level)

    def _on_user_text(self, text: str) -> None:
        self._last_activity = time.monotonic()
        self._assistant_turn = ""
        self.player.stop()  # barge-in: użytkownik przerywa → cisza dla modelu
        t = text.strip()
        if not t:
            return
        if self.settings.memory.enabled:
            try:
                self.memory.log_conversation("live", "user", t)
            except Exception:  # noqa: BLE001
                pass
        if self.cb.on_user_text:
            self.cb.on_user_text(t)
        asyncio.get_running_loop().create_task(self._scan_user_text(t))

    async def _scan_user_text(self, text: str) -> None:
        """Zgoda/odmowa na pending akcję + wykrywanie pożegnania."""
        t = text.lower()
        if self._pending is not None:
            deny = any(re.search(p, t) for p in config_mod.DENY_PATTERNS)
            consent = any(re.search(p, t) for p in config_mod.CONSENT_PATTERNS)
            if deny:
                self._pending = None
                if self.cb.on_consent_resolved:
                    self.cb.on_consent_resolved("anulowano")
                await self._safe_send_text("(Użytkownik odmówił — akcja została anulowana. Kontynuuj rozmowę.)")
                return
            if consent:
                await self._execute_pending()

        if not self._pending:
            for p in config_mod.GOODBYE_PATTERNS:
                if re.search(p, t):
                    self.log.info("Wykryto pożegnanie: %r", text)
                    self.stop("goodbye", farewell=True)
                    return

    async def _safe_send_text(self, text: str) -> None:
        if self._client is None:
            return
        try:
            await self._client.send_text(text)
        except Exception:  # noqa: BLE001
            pass

    # ------------------------------------------------------------- tool calls
    def _on_tool_calls(self, calls: list[dict]) -> None:
        self._last_activity = time.monotonic()
        asyncio.get_running_loop().create_task(self._handle_tool_calls(calls))

    async def _handle_tool_calls(self, calls: list[dict]) -> None:
        for call in calls:
            name = call.get("name") or ""
            args = call.get("args") or {}
            call_id = call.get("id") or ""
            tool = self.tools_ctx.get_tool(name)

            if name == "end_session":
                await self._respond([{"name": name, "id": call_id,
                                      "response": {"result": {"status": "ok", "output": "Sesja zakończona."}}}])
                self.stop("goodbye", farewell=True)
                return

            if tool is None:
                await self._respond([{"name": name, "id": call_id,
                                      "response": {"error": f"Nieznane narzędzie: {name}"}}])
                continue

            if name == "take_screenshot":
                try:
                    jpeg = await asyncio.to_thread(self.tools_ctx.capture_screenshot)
                    if jpeg and self._client is not None:
                        await self._client.send_video(jpeg)
                    await self._respond([{"name": name, "id": call_id, "response": {"result": {
                        "status": "ok", "output": "Zrzut ekranu wysłany. Przeanalizuj widok."}}}])
                except Exception as e:  # noqa: BLE001
                    await self._respond([{"name": name, "id": call_id, "response": {"result": {
                        "status": "error", "output": f"Nie udało się zrobić zrzutu: {e}"}}}])
                continue

            if self._requires_confirmation(tool):
                if self._pending is None:
                    desc = describe_action(name, args)
                    self._pending = (tool, args, call_id, name)
                    if self.cb.on_consent_required:
                        self.cb.on_consent_required(desc)
                    self.player.play_chime("confirm")
                    await self._respond([{"name": name, "id": call_id, "response": {"result": {
                        "status": "requires_confirmation",
                        "output": ("Ta akcja wymaga zgody użytkownika. Powiedz krótko, co chcesz zrobić, "
                                   "i zapytaj: „Mogę to zrobić?”. Poczekaj na odpowiedź — nie wywołuj narzędzia ponownie.")}}}])
                else:
                    await self._respond([{"name": name, "id": call_id, "response": {"result": {
                        "status": "pending_other", "output": "Inna akcja czeka na potwierdzenie."}}}])
                continue

            await self._execute_and_respond(tool, args, call_id)

    def _requires_confirmation(self, tool) -> bool:
        s = self.settings.safety
        if tool.confirm == "never":
            return False
        if s.confirm_all and tool.confirm != "never":
            return True
        if tool.confirm == "risky":
            if tool.name == "type_text":
                return s.confirm_typing or s.confirm_risky
            return s.confirm_risky
        return False

    async def _execute_pending(self) -> None:
        if self._pending is None:
            return
        tool, args, call_id, name = self._pending
        self._pending = None
        await self._execute_and_respond(tool, args, call_id, from_consent=True)
        if self.cb.on_consent_resolved:
            self.cb.on_consent_resolved("wykonano")

    async def _execute_and_respond(self, tool, args: dict, call_id: str, from_consent: bool = False) -> None:
        desc = describe_action(tool.name, args)
        if self.cb.on_action:
            self.cb.on_action(tool.name, False, desc)
        try:
            result = await asyncio.wait_for(
                asyncio.to_thread(tool.execute, args, self.tools_ctx),
                timeout=getattr(tool, "timeout", 20.0),
            )
        except asyncio.TimeoutError:
            result = {"status": "error", "output": "Akcja trwała zbyt długo i została przerwana."}
        except Exception as e:  # noqa: BLE001
            self.log.exception("Błąd narzędzia %s", tool.name)
            result = {"status": "error", "output": str(e)}
        self._last_activity = time.monotonic()
        ok = bool(result.get("status") == "ok")
        if self.cb.on_action:
            self.cb.on_action(tool.name, True, f"{desc} → {'✓' if ok else '✗'}")
        if self._client is not None:
            await self._respond([{"name": tool.name, "id": call_id, "response": {"result": result}}])
            if from_consent:
                note = (f"(Użytkownik potwierdził zgodę. Akcja „{desc}” została właśnie wykonana. "
                        "Poinformuj go o wyniku krótko.)")
                try:
                    await self._client.send_text(note)
                except Exception:  # noqa: BLE001
                    pass

    async def _respond(self, function_responses: list[dict]) -> None:
        if self._client is None:
            return
        try:
            await self._client.send_tool_response(function_responses)
        except Exception as e:  # noqa: BLE001
            self.log.warning("Nie udało się wysłać odpowiedzi narzędzia: %s", e)

    # ------------------------------------------------------------ pętle tła
    async def _feed_audio_loop(self) -> None:
        while not self._end_evt.is_set():
            try:
                frames = await asyncio.to_thread(self._audio_q.get, True, 0.2)
            except queue.Empty:
                continue
            if self._client is None:
                continue
            try:
                await self._client.send_audio(frames)
            except Exception:  # noqa: BLE001
                pass

    async def _watchdog_loop(self) -> None:
        timeout = max(10.0, float(self.settings.session.silence_timeout_s))
        while not self._end_evt.is_set():
            await asyncio.sleep(5.0)
            idle = time.monotonic() - self._last_activity
            if idle > timeout and self._client is not None:
                self.log.info("Cisza przez %.0f s — kończę sesję.", idle)
                self.stop("silence", farewell=True)

    async def _duration_guard(self) -> None:
        s = self.settings.session
        while not self._end_evt.is_set():
            await asyncio.sleep(10.0)
            elapsed = time.monotonic() - self._started_at
            if elapsed > s.max_duration_s:
                self.stop("timeout", farewell=True)
                return
            if elapsed > s.reconnect_at_s and not self._reconnected_once and self._client is not None:
                self._reconnected_once = True
                self.log.info("Limit ~10 min połączenia — ponowne łączenie…")
                old = self._client
                self._client = None
                try:
                    await old.close()
                except Exception:  # noqa: BLE001
                    pass
                if await self._connect(retries=2):
                    if self.cb.on_reconnected:
                        self.cb.on_reconnected()
                    await self._safe_send_text("(Chwilowa przerwa techniczna — kontynuujemy rozmowę.)")

    async def _vision_loop(self) -> None:
        if not self.settings.vision.enabled:
            return
        interval = max(2.0, float(self.settings.vision.interval_s))
        first = True
        while not self._end_evt.is_set():
            if self._client is not None and self._vision_frames < self.settings.vision.max_frames:
                try:
                    jpeg = await asyncio.to_thread(self.tools_ctx.capture_screenshot)
                    if jpeg:
                        await self._client.send_video(jpeg)
                        self._vision_frames += 1
                except Exception:  # noqa: BLE001
                    pass
            await asyncio.sleep(0.1 if first else interval)
            first = False

    # --------------------------------------------------------------- pomoc
    def _on_interrupted(self) -> None:
        self._model_speaking = False
        if self.cb.on_interrupted:
            self.cb.on_interrupted()

    def _on_assistant_text(self, text: str) -> None:
        self._last_activity = time.monotonic()
        self._assistant_turn += text
        if self.cb.on_assistant_text:
            self.cb.on_assistant_text(text)

    def _on_turn_complete(self) -> None:
        self._model_speaking = False
        if self._assistant_turn.strip():
            if self.settings.memory.enabled:
                try:
                    self.memory.log_conversation("live", "assistant", self._assistant_turn.strip())
                except Exception:  # noqa: BLE001
                    pass
            if self.cb.on_assistant_final:
                self.cb.on_assistant_final(self._assistant_turn.strip())
            self._assistant_turn = ""

    def _on_goaway(self) -> None:
        self.log.info("Live API: goAway — ponowne połączenie.")
        old = self._client
        self._client = None
        # Zamknij poprzedni socket, aby jego pętla odbioru nie została osierocona
        # po utworzeniu nowego połączenia.
        if old is not None:
            asyncio.get_running_loop().create_task(old.close())
        asyncio.get_running_loop().create_task(self._reconnect_soon())

    async def _reconnect_soon(self) -> None:
        await asyncio.sleep(2.0)
        if self._end_evt.is_set():
            return
        if await self._connect(retries=2):
            if self.cb.on_reconnected:
                self.cb.on_reconnected()
            await self._safe_send_text("(Przerwano i wznowiono połączenie — kontynuuj rozmowę.)")

    async def _finalize(self, farewell: bool = False) -> None:
        if self._client is not None and not self._farewell_sent:
            self._farewell_sent = True
            if farewell:
                await self._safe_send_text("Użytkownik kończy rozmowę. Pożegnaj się bardzo krótko.")
                try:
                    for _ in range(12):
                        await asyncio.sleep(0.5)
                        if not self._model_speaking:
                            break
                except Exception:  # noqa: BLE001
                    pass
            try:
                await self._client.close()
            except Exception:  # noqa: BLE001
                pass
            self._client = None
