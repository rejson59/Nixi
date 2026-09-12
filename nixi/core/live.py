"""Klient Gemini Live API (WebSocket) — natywny protokół `gemini-3.1-flash-live-preview`.

Implementacja zgodna z oficjalną dokumentacją WebSocket (wersja 2026):
  wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=...
"""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import re
from dataclasses import dataclass, field
from typing import Any, Callable

import websockets

WS_URL = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key={key}"
)

_RATE_RE = re.compile(r"rate=(\d+)")


@dataclass
class LiveCallbacks:
    on_ready: Callable[[], None] = None
    on_audio: Callable[[bytes, str], None] = None          # (PCM int16 LE, mimeType)
    on_user_text: Callable[[str], None] = None             # transkrypcja użytkownika
    on_assistant_text: Callable[[str], None] = None        # transkrypcja Nixi (przyrosty)
    on_tool_calls: Callable[[list[dict]], None] = None
    on_interrupted: Callable[[], None] = None
    on_turn_complete: Callable[[], None] = None
    on_goaway: Callable[[], None] = None
    on_closed: Callable[[int, str], None] = None
    on_error: Callable[[str], None] = None


class GeminiLiveClient:
    def __init__(
        self,
        api_key: str,
        model: str,
        system_instruction: str,
        tools: list[dict],
        callbacks: LiveCallbacks,
        logger: logging.Logger | None = None,
        ws_url: str | None = None,
    ):
        self.log = logger or logging.getLogger("nixi.live")
        self.api_key = api_key
        self.model = model
        self.system_instruction = system_instruction
        self.tools = tools or []
        self.cb = callbacks or LiveCallbacks()
        self.ws_url = ws_url or WS_URL
        self.ws = None
        self._send_lock = asyncio.Lock()
        self._ready = asyncio.Event()
        self._closed_evt = asyncio.Event()
        self._recv_task: asyncio.Task | None = None
        self._close_code = 1000
        self._close_reason = ""

    # ------------------------------------------------------------- łączność
    async def connect(self, timeout_s: float = 30.0) -> bool:
        """Połącz, wyślij setup i uruchom odbiór; czeka na setupComplete."""
        url = self.ws_url.format(key=self.api_key)
        try:
            self.ws = await websockets.connect(
                url, max_size=32 * 1024 * 1024, ping_interval=15, ping_timeout=20,
                close_timeout=3, open_timeout=15,
            )
        except Exception as e:  # noqa: BLE001
            self.log.warning("Nie udało się połączyć z Live API: %s", e)
            if self.cb.on_error:
                self.cb.on_error(f"Brak połączenia z Gemini: {e}")
            return False
        self._recv_task = asyncio.create_task(self.run())
        await self._send(self._setup_message())
        try:
            await asyncio.wait_for(self._ready.wait(), timeout=timeout_s)
            return True
        except asyncio.TimeoutError:
            if self.cb.on_error:
                self.cb.on_error("Gemini nie potwierdziło sesji (timeout).")
            await self.close()
            return False

    async def wait(self) -> None:
        """Poczekaj na koniec pętli odbioru (po close())."""
        if self._recv_task is not None:
            try:
                await self._recv_task
            except Exception:  # noqa: BLE001
                pass

    def _setup_message(self) -> dict:
        return {
            "setup": {
                "model": f"models/{self.model}",
                "responseModalities": ["AUDIO"],
                "systemInstruction": {"parts": [{"text": self.system_instruction}]},
                "tools": [{"functionDeclarations": self.tools}],
                "inputAudioTranscription": {},
                "outputAudioTranscription": {},
            }
        }

    # -------------------------------------------------------------- wysyłka
    async def _send(self, msg: dict) -> None:
        if self.ws is None:
            return
        async with self._send_lock:
            await self.ws.send(json.dumps(msg, ensure_ascii=False))

    async def send_audio(self, pcm_int16: bytes, mime: str = "audio/pcm;rate=16000") -> None:
        if not pcm_int16:
            return
        await self._send({
            "realtimeInput": {
                "audio": {"data": base64.b64encode(pcm_int16).decode("ascii"), "mimeType": mime}
            }
        })

    async def send_video(self, jpeg_bytes: bytes, mime: str = "image/jpeg") -> None:
        if not jpeg_bytes:
            return
        await self._send({
            "realtimeInput": {
                "video": {"data": base64.b64encode(jpeg_bytes).decode("ascii"), "mimeType": mime}
            }
        })

    async def send_text(self, text: str) -> None:
        if not text:
            return
        await self._send({"realtimeInput": {"text": text}})

    async def send_tool_response(self, function_responses: list[dict]) -> None:
        await self._send({"toolResponse": {"functionResponses": function_responses}})

    # ------------------------------------------------------------ odbiór
    async def run(self) -> None:
        """Pętla odbioru — kończy się, gdy WS zostanie zamknięty."""
        if self.ws is None:
            return
        try:
            async for raw in self.ws:
                try:
                    msg = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                await self._handle(msg)
        except websockets.ConnectionClosed as e:
            self._close_code = e.code
            self._close_reason = e.reason or ""
            self.log.info("Live API zamknęło połączenie: %s %s", e.code, e.reason)
        except Exception as e:  # noqa: BLE001
            self._close_code = 1006
            self._close_reason = str(e)
            self.log.warning("Błąd połączenia Live API: %s", e)
        finally:
            self._closed_evt.set()
            if self.cb.on_closed:
                try:
                    self.cb.on_closed(self._close_code, self._close_reason)
                except Exception:  # noqa: BLE001
                    pass

    async def _handle(self, msg: dict) -> None:
        if "setupComplete" in msg:
            self._ready.set()
            if self.cb.on_ready:
                self.cb.on_ready()
            return
        if "goAway" in msg:
            self.log.info("Live API prosi o ponowne połączenie (goAway).")
            if self.cb.on_goaway:
                self.cb.on_goaway()
            return
        if "setupFailure" in msg:
            err = msg.get("setupFailure", {})
            if self.cb.on_error:
                self.cb.on_error(f"Gemini odrzuciło konfigurację: {err}")
            return

        if "toolCall" in msg:
            calls = msg["toolCall"].get("functionCalls") or []
            if calls and self.cb.on_tool_calls:
                self.cb.on_tool_calls(calls)
            return

        sc = msg.get("serverContent")
        if not sc:
            return

        if sc.get("interrupted") and self.cb.on_interrupted:
            self.cb.on_interrupted()

        it = sc.get("inputTranscription")
        if it and it.get("text") and self.cb.on_user_text:
            self.cb.on_user_text(it["text"].strip())

        ot = sc.get("outputTranscription")
        if ot and ot.get("text") and self.cb.on_assistant_text:
            self.cb.on_assistant_text(ot["text"].strip())

        legacy_calls: list[dict] = []
        for part in (sc.get("modelTurn") or {}).get("parts") or []:
            if "inlineData" in part:
                data = part["inlineData"].get("data") or ""
                mime = part["inlineData"].get("mimeType") or ""
                if data and self.cb.on_audio:
                    try:
                        self.cb.on_audio(base64.b64decode(data), mime)
                    except Exception:  # noqa: BLE001
                        pass
            elif "text" in part and part["text"] and self.cb.on_assistant_text:
                self.cb.on_assistant_text(part["text"].strip())
            elif "functionCall" in part:
                legacy_calls.append(part["functionCall"])
        if legacy_calls and self.cb.on_tool_calls:
            self.cb.on_tool_calls(legacy_calls)

        if sc.get("turnComplete") and self.cb.on_turn_complete:
            self.cb.on_turn_complete()

    async def close(self) -> None:
        if self.ws is not None:
            try:
                await self.ws.close()
            except Exception:  # noqa: BLE001
                pass
            self.ws = None
