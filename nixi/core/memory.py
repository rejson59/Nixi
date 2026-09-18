"""Pamięć długotrwała Nixi (SQLite + FTS5, opcjonalnie semantyczna przez Gemini Embeddings).

Hierarchia wyszukiwania:
  1. wektory (Gemini embedding API) — jeśli dostępny klucz API i sieć,
  2. FTS5 + trafność/świeżość — zawsze działa offline.

Wszystko w lokalnej bazie SQLite — pamięć trwa między sesjami i restartami.
"""
from __future__ import annotations

import json
import logging
import re
import sqlite3
import threading
import time

_FOLD_MAP = str.maketrans("ąćęłńóśźżĄĆĘŁŃÓŚŹŻ", "acelnoszzACELNOSZZ")


def fold(text: str) -> str:
    """ASCII-fold (bez polskich znaków) — do indeksu FTS."""
    return (text or "").translate(_FOLD_MAP).lower()


_SCHEMA = """
CREATE TABLE IF NOT EXISTS memories(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts REAL NOT NULL,
  content TEXT NOT NULL,
  category TEXT NOT NULL DEFAULT 'fact',
  meta TEXT NOT NULL DEFAULT '{}',
  embedding BLOB
);
CREATE TABLE IF NOT EXISTS notes(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts REAL NOT NULL,
  title TEXT NOT NULL,
  content TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS conversations(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts REAL NOT NULL,
  session_id TEXT,
  role TEXT NOT NULL,
  text TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS kv(
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
-- Indeks pełnotekstowy trzyma treść ASCII-składaną (bez polskich znaków),
-- bo zapytania też są składane przez fold(). Wcześniej indeks przechowywał
-- oryginał, więc „zespol” nigdy nie trafiało w „zespół”.
CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
  content_folded, category, tokenize='unicode61'
);
"""

# Stary (wadliwy) indeks: external-content FTS na nieskładanej treści.
_LEGACY_FTS_MARKERS = ("content='memories'", "content=\"memories\"")


class MemoryStore:
    def __init__(self, db_path, logger: logging.Logger | None = None):
        self.log = logger or logging.getLogger("nixi.memory")
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(str(db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._migrate_legacy_fts()
            self._conn.executescript(_SCHEMA)
            self._reindex_if_empty()
            self._conn.commit()

    # -------------------------------------------------------------- migracja
    def _migrate_legacy_fts(self) -> None:
        """Usuń stary indeks FTS (i jego triggery), jeśli baza pochodzi z wcześniejszej wersji."""
        row = self._conn.execute(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='memories_fts'"
        ).fetchone()
        if row is None:
            return
        sql = row["sql"] or ""
        if not any(marker in sql for marker in _LEGACY_FTS_MARKERS):
            return
        self.log.info("Przebudowuję indeks pamięci (nowy format, obsługa polskich znaków).")
        self._conn.executescript(
            "DROP TRIGGER IF EXISTS memories_ai;"
            "DROP TRIGGER IF EXISTS memories_ad;"
            "DROP TABLE IF EXISTS memories_fts;"
        )

    def _reindex_if_empty(self) -> None:
        """Zbuduj indeks od zera, gdy jest pusty, a wspomnienia już istnieją."""
        n_fts = self._conn.execute("SELECT COUNT(*) c FROM memories_fts").fetchone()["c"]
        n_mem = self._conn.execute("SELECT COUNT(*) c FROM memories").fetchone()["c"]
        if n_fts or not n_mem:
            return
        rows = self._conn.execute("SELECT id, content, category FROM memories").fetchall()
        self._conn.executemany(
            "INSERT INTO memories_fts(rowid, content_folded, category) VALUES(?,?,?)",
            [(r["id"], fold(r["content"]), r["category"]) for r in rows],
        )

    # ------------------------------------------------------------- pomocnicze
    def _execute(self, sql: str, params=()) -> list[sqlite3.Row]:
        with self._lock:
            cur = self._conn.execute(sql, params)
            self._conn.commit()
            return cur.fetchall()

    # --------------------------------------------------------------- pamięć
    def add_memory(self, content: str, category: str = "fact", meta: dict | None = None,
                   embedding: bytes | None = None) -> int:
        content = (content or "").strip()
        if not content:
            return -1
        with self._lock:
            cur = self._conn.execute(
                "INSERT INTO memories(ts, content, category, meta, embedding) VALUES(?,?,?,?,?)",
                (time.time(), content, category, json.dumps(meta or {}, ensure_ascii=False), embedding),
            )
            mid = int(cur.lastrowid)
            self._conn.execute(
                "INSERT INTO memories_fts(rowid, content_folded, category) VALUES(?,?,?)",
                (mid, fold(content), category),
            )
            self._conn.commit()
        self.log.info("Pamięć #%d: %s", mid, content[:80])
        return mid

    def update_embedding(self, memory_id: int, embedding: bytes) -> None:
        with self._lock:
            self._conn.execute("UPDATE memories SET embedding=? WHERE id=?", (embedding, memory_id))
            self._conn.commit()

    def recall(self, query: str, k: int = 6) -> list[dict]:
        query = (query or "").strip()
        with self._lock:
            if not query:
                rows = self._conn.execute(
                    "SELECT id, ts, content, category, meta, embedding FROM memories ORDER BY ts DESC LIMIT ?", (k,)
                ).fetchall()
            else:
                # najpierw spróbuj FTS (szybko, zawsze działa)
                folded = fold(query)
                tokens = [t for t in re.split(r"\W+", folded) if len(t) > 1][:12]
                if not tokens:
                    tokens = [folded]
                match = " OR ".join(f'"{t}"' for t in tokens)
                try:
                    rows = self._conn.execute(
                        """
                        SELECT m.id, m.ts, m.content, m.category, m.meta, m.embedding,
                               bm25(memories_fts, 6.0, 1.0) AS score
                        FROM memories_fts JOIN memories m ON m.id = memories_fts.rowid
                        WHERE memories_fts MATCH ?
                        ORDER BY score
                        LIMIT ?
                        """,
                        (match, k * 3),
                    ).fetchall()
                except sqlite3.OperationalError as e:
                    # np. zapytanie ze składnią FTS, której nie da się sparsować
                    self.log.warning("Zapytanie FTS odrzucone (%s) — używam ostatnich wpisów.", e)
                    rows = []
                if not rows:
                    rows = self._conn.execute(
                        "SELECT id, ts, content, category, meta, embedding FROM memories ORDER BY ts DESC LIMIT ?",
                        (k,),
                    ).fetchall()
        results = []
        for r in rows:
            results.append({
                "id": r["id"],
                "ts": r["ts"],
                "content": r["content"],
                "category": r["category"],
                "meta": json.loads(r["meta"] or "{}"),
                "has_embedding": bool(r["embedding"]),
            })
        return results[:k]

    def all_with_embeddings(self, limit: int = 500) -> list[dict]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT id, ts, content, category, meta, embedding FROM memories WHERE embedding IS NOT NULL LIMIT ?",
                (limit,),
            ).fetchall()
        return [{"id": r["id"], "ts": r["ts"], "content": r["content"], "embedding": bytes(r["embedding"])} for r in rows]

    def recent(self, n: int = 5) -> list[dict]:
        return self.recall("", n)

    def forget(self, memory_id: int) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM memories WHERE id=?", (memory_id,))
            self._conn.execute("DELETE FROM memories_fts WHERE rowid=?", (memory_id,))
            self._conn.commit()

    # --------------------------------------------------------------- notatki
    def add_note(self, title: str, content: str = "") -> int:
        with self._lock:
            cur = self._conn.execute(
                "INSERT INTO notes(ts, title, content) VALUES(?,?,?)",
                (time.time(), (title or "").strip(), (content or "").strip()),
            )
            self._conn.commit()
            return int(cur.lastrowid)

    def read_notes(self, limit: int = 10) -> list[dict]:
        with self._lock:
            rows = self._conn.execute("SELECT id, ts, title, content FROM notes ORDER BY ts DESC LIMIT ?", (limit,)).fetchall()
        return [{"id": r["id"], "title": r["title"], "content": r["content"], "ts": r["ts"]} for r in rows]

    # ----------------------------------------------------------- rozmowy/logi
    def log_conversation(self, session_id: str, role: str, text: str) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO conversations(ts, session_id, role, text) VALUES(?,?,?,?)",
                (time.time(), session_id, role, (text or "").strip()),
            )
            self._conn.commit()

    def recent_dialog(self, n: int = 4) -> list[dict]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT role, text FROM conversations ORDER BY id DESC LIMIT ?", (n,)
            ).fetchall()
        return [{"role": r["role"], "text": r["text"]} for r in reversed(rows)]

    # ------------------------------------------------------------------- kv
    def get_kv(self, key: str, default: str | None = None) -> str | None:
        with self._lock:
            row = self._conn.execute("SELECT value FROM kv WHERE key=?", (key,)).fetchone()
        return row["value"] if row else default

    def set_kv(self, key: str, value: str) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO kv(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, str(value)),
            )
            self._conn.commit()

    def stats(self) -> dict:
        with self._lock:
            n_mem = self._conn.execute("SELECT COUNT(*) c FROM memories").fetchone()["c"]
            n_notes = self._conn.execute("SELECT COUNT(*) c FROM notes").fetchone()["c"]
            n_conv = self._conn.execute("SELECT COUNT(*) c FROM conversations").fetchone()["c"]
        return {"memories": n_mem, "notes": n_notes, "conversations": n_conv}

    def close(self) -> None:
        with self._lock:
            try:
                self._conn.commit()
                self._conn.close()
            except Exception:  # noqa: BLE001
                pass
