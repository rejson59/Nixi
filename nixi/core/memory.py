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
CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
  content, category, content='memories', content_rowid='id', tokenize='unicode61'
);
CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN
  INSERT INTO memories_fts(rowid, content, category)
  VALUES (new.id, new.content, new.category);
END;
CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN
  INSERT INTO memories_fts(memories_fts, rowid, content, category)
  VALUES ('delete', old.id, old.content, old.category);
END;
"""


class MemoryStore:
    def __init__(self, db_path, logger: logging.Logger | None = None):
        self.log = logger or logging.getLogger("nixi.memory")
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(str(db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.executescript(_SCHEMA)
            self._conn.commit()

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
            self._conn.commit()
            mid = int(cur.lastrowid)
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
