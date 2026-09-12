"""Maszyna stanów Nixi — czysta, testowalna logika przejść.

Stany:
  BOOT        — start aplikacji (zanim nasłuch ruszy)
  IDLE        — czuwanie: nasłuch wyłącznie wake worda („Hej Nixi”),
                zero audio wychodzi do sieci, zero wykonywanych akcji
  WAKING      — wykryto wake word, otwieranie połączenia (WebSocket) / demo
  LISTENING   — sesja aktywna, mikrofon streamowany do modelu
  THINKING    — wykonywanie akcji/tool calli lokalnie
  SPEAKING    — model mówi (audio z modelu leci do głośników)
  CONFIRMING  — czekamy na zgodę użytkownika na akcję
  DEMO        — tryb demo (brak klucza API) — symulowana sesja
  ERROR       — błąd krytyczny (np. brak połączenia)
"""
from __future__ import annotations

from enum import Enum, auto


class State(Enum):
    BOOT = auto()
    IDLE = auto()
    WAKING = auto()
    LISTENING = auto()
    THINKING = auto()
    SPEAKING = auto()
    CONFIRMING = auto()
    DEMO = auto()
    ERROR = auto()


class Event(Enum):
    RESET = auto()          # powrót do czuwania (np. po błędzie)
    WAKE_DETECTED = auto()  # usłyszano „Hej Nixi”
    DEMO_STARTED = auto()   # sesja demo (brak klucza API)
    SESSION_STARTED = auto()
    USER_TALKING = auto()
    MODEL_TALKING = auto()
    MODEL_IDLE = auto()
    ACTION_STARTED = auto()
    ACTION_DONE = auto()
    CONSENT_REQUESTED = auto()
    CONSENT_GRANTED = auto()
    CONSENT_DENIED = auto()
    SESSION_ENDED = auto()  # pożegnanie / cisza / koniec sesji
    ERROR = auto()
    SHUTDOWN = auto()


STATE_LABELS = {
    State.BOOT: "Uruchamianie…",
    State.IDLE: "Czuwam — powiedz „Hej Nixi”",
    State.WAKING: "Hej! Łączę się…",
    State.LISTENING: "Słucham…",
    State.THINKING: "Wykonuję…",
    State.SPEAKING: "Mówię…",
    State.CONFIRMING: "Czekam na zgodę…",
    State.DEMO: "Demo — dodaj klucz API",
    State.ERROR: "Błąd",
}

# Zbiór stanów, w których sesja jest "aktywna" (mikrofon leci do modelu).
ACTIVE_STATES = {State.WAKING, State.LISTENING, State.THINKING, State.SPEAKING, State.CONFIRMING, State.DEMO}


def _noop(*states: State) -> State:
    """Zdarzenie bez zmiany stanu — zwracamy ten sam stan."""
    return states[0]


# Tablica przejść: (state, event) -> nowy stan; brak wpisu = niedozwolone przejście.
_TRANSITIONS: dict[tuple[State, Event], State] = {}

_NOOP_EVENTS: dict[Event, tuple[State, ...]] = {
    Event.USER_TALKING: (State.LISTENING, State.THINKING),
    Event.MODEL_TALKING: (State.SPEAKING,),
}

for _e, _states in _NOOP_EVENTS.items():
    for _s in _states:
        _TRANSITIONS[(_s, _e)] = _s

_TRANSITIONS.update({
    (State.BOOT, Event.RESET): State.IDLE,
    (State.IDLE, Event.WAKE_DETECTED): State.WAKING,
    (State.IDLE, Event.SESSION_STARTED): State.DEMO,       # bez klucza API → demo
    (State.IDLE, Event.ERROR): State.ERROR,
    (State.WAKING, Event.SESSION_STARTED): State.LISTENING,
    (State.WAKING, Event.DEMO_STARTED): State.DEMO,
    (State.WAKING, Event.SESSION_ENDED): State.IDLE,
    (State.WAKING, Event.ERROR): State.ERROR,
    (State.WAKING, Event.RESET): State.IDLE,
    (State.LISTENING, Event.MODEL_TALKING): State.SPEAKING,
    (State.LISTENING, Event.ACTION_STARTED): State.THINKING,
    (State.LISTENING, Event.CONSENT_REQUESTED): State.CONFIRMING,
    (State.LISTENING, Event.SESSION_ENDED): State.IDLE,
    (State.LISTENING, Event.ERROR): State.ERROR,
    (State.THINKING, Event.ACTION_DONE): State.LISTENING,
    (State.THINKING, Event.MODEL_TALKING): State.SPEAKING,
    (State.THINKING, Event.SESSION_ENDED): State.IDLE,
    (State.THINKING, Event.ERROR): State.ERROR,
    (State.SPEAKING, Event.USER_TALKING): State.LISTENING,  # barge-in
    (State.SPEAKING, Event.MODEL_IDLE): State.LISTENING,
    (State.SPEAKING, Event.SESSION_ENDED): State.IDLE,
    (State.SPEAKING, Event.ERROR): State.ERROR,
    (State.CONFIRMING, Event.CONSENT_GRANTED): State.THINKING,
    (State.CONFIRMING, Event.CONSENT_DENIED): State.LISTENING,
    (State.CONFIRMING, Event.SESSION_ENDED): State.IDLE,
    (State.CONFIRMING, Event.RESET): State.IDLE,
    (State.CONFIRMING, Event.ERROR): State.ERROR,
    (State.LISTENING, Event.RESET): State.IDLE,
    (State.THINKING, Event.RESET): State.IDLE,
    (State.SPEAKING, Event.RESET): State.IDLE,
    (State.DEMO, Event.SESSION_ENDED): State.IDLE,
    (State.DEMO, Event.RESET): State.IDLE,
    (State.DEMO, Event.ERROR): State.ERROR,
    (State.ERROR, Event.RESET): State.IDLE,
})


class StateError(RuntimeError):
    pass


def advance(state: State, event: Event) -> State:
    """Wykonaj przejście; niedozwolone przejście → StateError.

    Dzięki temu Nixi nigdy nie wykona akcji w złym stanie (np. tool call w IDLE).
    """
    try:
        return _TRANSITIONS[(state, event)]
    except KeyError:
        raise StateError(f"Niedozwolone przejście: {state.name} + {event.name}")


def label(state: State) -> str:
    return STATE_LABELS.get(state, state.name)


def is_active(state: State) -> bool:
    return state in ACTIVE_STATES
