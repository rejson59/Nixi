"""Narzędzia pamięci długotrwałej i notatek."""
from __future__ import annotations


def _ok(output: str, **extra) -> dict:
    return {"status": "ok", "output": output, **extra}


def _err(output: str) -> dict:
    return {"status": "error", "output": output}


def remember_memory(args: dict, ctx) -> dict:
    content = (args.get("content") or "").strip()
    category = (args.get("category") or "fact").strip()
    if not content:
        return _err("Brak treści do zapamiętania.")
    try:
        mid = ctx.memory.add_memory(content, category=category or "fact")
        # spróbuj dodać osadzenie semantyczne (opcjonalnie)
        if ctx.settings.api_key:
            try:
                from ..core import embeddings

                vec = embeddings.embed_text(ctx.settings.api_key, ctx.settings.memory.embed_model, content, ctx.log, 4.0)
                if vec:
                    ctx.memory.update_embedding(mid, embeddings.pack_embedding(vec))
            except Exception:  # noqa: BLE001
                pass
        ui = ctx.hooks.get("ui_note")
        if ui:
            try:
                ui(f"Zapamiętałam: {content[:80]}")
            except Exception:  # noqa: BLE001
                pass
        return _ok(f"Zapamiętałam: {content}")
    except Exception as e:  # noqa: BLE001
        return _err(f"Nie udało się zapamiętać: {e}")


def recall_memory(args: dict, ctx) -> dict:
    query = (args.get("query") or "").strip()
    k = max(1, min(20, int(args.get("k") or 5)))
    try:
        items = ctx.memory.recall(query, k)
        if not items:
            return _ok("Nie mam żadnych zapisanych wspomnień na ten temat.")
        lines = [f"- ({m['category']}) {m['content']}" for m in items]
        return _ok("Wspomnienia:\n" + "\n".join(lines), items=len(items))
    except Exception as e:  # noqa: BLE001
        return _err(f"Błąd odczytu pamięci: {e}")


def create_note(args: dict, ctx) -> dict:
    title = (args.get("title") or "").strip()
    content = (args.get("content") or "").strip()
    if not title:
        return _err("Brak tytułu notatki.")
    try:
        nid = ctx.memory.add_note(title, content)
        return _ok(f"Notatka „{title}” zapisana (id {nid}).")
    except Exception as e:  # noqa: BLE001
        return _err(f"Nie udało się zapisać notatki: {e}")


def read_notes(args: dict, ctx) -> dict:
    limit = max(1, min(50, int(args.get("limit") or 5)))
    try:
        notes = ctx.memory.read_notes(limit)
        if not notes:
            return _ok("Brak notatek.")
        lines = [f"- {n['title']}: {n['content']}" for n in notes]
        return _ok("Notatki:\n" + "\n".join(lines))
    except Exception as e:  # noqa: BLE001
        return _err(f"Błąd odczytu notatek: {e}")
