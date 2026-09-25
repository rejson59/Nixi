package dev.nixi.tools

import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.util.LogBus
import org.json.JSONObject

/** Listy życia: todos, zakupy, ludzie, zadania ze szkoły. */
object LifeTools {

    suspend fun run(action: String, kind: String, title: String, extra: String): ToolResult {
        if (!SupabaseHub.available) {
            return ToolResult.fail("Supabase niedostępny — nie mogę teraz zapisać listy.")
        }
        val k = kind.trim().lowercase()
        val a = action.trim().lowercase()
        return when {
            k in listOf("todo", "todos", "zadanie", "zadania", "homework", "praca", "lekcje") ->
                todos(a, title, extra, school = k in listOf("homework", "praca", "lekcje"))
            k in listOf("shop", "shopping", "zakupy", "zakup") -> shop(a, title, extra)
            k in listOf("person", "people", "ludzie", "osoba") -> people(a, title, extra)
            else -> ToolResult.fail("Podaj kind: todo, shopping, people albo homework.")
        }
    }

    private suspend fun todos(action: String, title: String, extra: String, school: Boolean): ToolResult {
        val cat = if (school) "szkoła" else extra.ifBlank { "ogólne" }
        return when (action) {
            "list", "lista", "" -> {
                val r = SupabaseHub.listRows(Tables.TODOS, orderBy = "created_at.desc", limit = 25)
                if (!r.ok) return ToolResult.fail(r.error ?: "Nie odczytałam todos.")
                val rows = r.rows
                if (rows.isEmpty()) ToolResult.ok("Lista zadań pusta.")
                else ToolResult.ok(
                    rows.joinToString("\n") { o ->
                        val d = if (o.optBoolean("done")) "✓" else "○"
                        "$d ${o.optString("title")} [${o.optString("category")}] id=${o.optString("id").take(8)}"
                    }
                )
            }
            "add", "dodaj" -> {
                if (title.isBlank()) return ToolResult.fail("Podaj treść zadania.")
                val row = JSONObject().put("title", title.trim()).put("category", cat).put("done", false)
                val r = SupabaseHub.insertRow(Tables.TODOS, row)
                if (!r.ok) ToolResult.fail(r.error ?: "Nie zapisałam.")
                else {
                    LogBus.log("life.todo", title.take(60))
                    ToolResult.ok("Dodałam zadanie: $title")
                }
            }
            "done", "zrobione" -> markDone(Tables.TODOS, title, "title")
            else -> ToolResult.fail("Akcja: list, add, done.")
        }
    }

    private suspend fun shop(action: String, title: String, extra: String): ToolResult = when (action) {
        "list", "lista", "" -> {
            val r = SupabaseHub.listRows(Tables.SHOPPING, orderBy = "created_at.desc", limit = 40)
            if (!r.ok) ToolResult.fail(r.error ?: "Nie odczytałam zakupów.")
            val rows = r.rows
            if (rows.isEmpty()) ToolResult.ok("Lista zakupów pusta.")
            else ToolResult.ok(
                rows.joinToString("\n") { o ->
                    val d = if (o.optBoolean("done")) "✓" else "○"
                    val q = o.optString("qty")
                    "$d ${o.optString("item")}${if (q.isNotBlank()) " ($q)" else ""}"
                }
            )
        }
        "add", "dodaj" -> {
            if (title.isBlank()) return ToolResult.fail("Podaj produkt.")
            val row = JSONObject().put("item", title.trim()).put("qty", extra).put("done", false)
            val r = SupabaseHub.insertRow(Tables.SHOPPING, row)
            if (!r.ok) ToolResult.fail(r.error ?: "Nie zapisałam.")
            else ToolResult.ok("Na listę zakupów: $title")
        }
        "done", "zrobione" -> markDone(Tables.SHOPPING, title, "item")
        else -> ToolResult.fail("Akcja: list, add, done.")
    }

    private suspend fun people(action: String, title: String, extra: String): ToolResult = when (action) {
        "list", "lista", "" -> {
            val r = SupabaseHub.listRows(Tables.PEOPLE, orderBy = "created_at.desc", limit = 30)
            if (!r.ok) ToolResult.fail(r.error ?: "Nie odczytałam ludzi.")
            val rows = r.rows
            if (rows.isEmpty()) ToolResult.ok("Nie mam jeszcze zapisanych osób.")
            else ToolResult.ok(
                rows.joinToString("\n") { o ->
                    "${o.optString("name")} (${o.optString("relation")}): ${o.optString("notes").take(80)}"
                }
            )
        }
        "add", "dodaj" -> {
            if (title.isBlank()) return ToolResult.fail("Podaj imię.")
            val row = JSONObject().put("name", title.trim()).put("relation", extra).put("notes", "")
            val r = SupabaseHub.insertRow(Tables.PEOPLE, row)
            if (!r.ok) ToolResult.fail(r.error ?: "Nie zapisałam.")
            else ToolResult.ok("Zapamiętałam osobę: $title")
        }
        else -> ToolResult.fail("Akcja: list, add.")
    }

    private suspend fun markDone(table: String, query: String, field: String): ToolResult {
        if (query.isBlank()) return ToolResult.fail("Podaj co odhaczyć.")
        val r = SupabaseHub.listRows(table, limit = 40)
        if (!r.ok) return ToolResult.fail(r.error ?: "brak listy")
        val q = query.lowercase()
        val hit = r.rows.firstOrNull { it.optString(field).lowercase().contains(q) }
            ?: return ToolResult.fail("Nie znalazłam „$query”.")
        val id = hit.optString("id")
        val u = SupabaseHub.updateRow(table, mapOf("id" to "eq.$id"), JSONObject().put("done", true))
        return if (u.ok) ToolResult.ok("Odhaczyłam: ${hit.optString(field)}")
        else ToolResult.fail(u.error ?: "Nie udało się odhaczyć.")
    }
}
