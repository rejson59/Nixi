package dev.nixi.tools

import dev.nixi.db.OfflineQueue
import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.util.LogBus
import org.json.JSONObject

/** Listy życia: todos, zakupy, ludzie, zadania ze szkoły. */
object LifeTools {

    suspend fun run(action: String, kind: String, title: String, extra: String): ToolResult {
        val k = kind.trim().lowercase()
        val a = action.trim().lowercase()
        if (a in listOf("backup", "kopia")) return dumpBackup()
        if (!SupabaseHub.available && a in listOf("list", "lista", "")) {
            return ToolResult.fail("Supabase niedostępny — nie odczytam listy. Dodawanie i tak zapiszę offline.")
        }
        return when {
            k in listOf("todo", "todos", "zadanie", "zadania", "homework", "praca", "note", "notatka", "notatki") ->
                todos(a, title, extra, school = k in listOf("homework", "praca"),
                    notes = k in listOf("note", "notatka", "notatki"))
            k in listOf("shop", "shopping", "zakupy", "zakup") -> shop(a, title, extra)
            k in listOf("person", "people", "ludzie", "osoba") -> people(a, title, extra)
            k in listOf("lesson", "lessons", "lekcje", "plan") -> lessons()
            else -> ToolResult.fail("Podaj kind: todo, shopping, people, homework, notes albo lessons.")
        }
    }

    private suspend fun todos(
        action: String, title: String, extra: String, school: Boolean, notes: Boolean = false,
    ): ToolResult {
        val cat = when {
            school -> "szkoła"
            notes -> "notatki"
            else -> extra.ifBlank { "ogólne" }
        }
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
                val r = runCatching { SupabaseHub.insertRow(Tables.TODOS, row) }.getOrNull()
                if (r == null || !r.ok) queued(Tables.TODOS, row, "zadanie: $title")
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
            if (title.isBlank()) ToolResult.fail("Podaj produkt.")
            else {
                val row = JSONObject().put("item", title.trim()).put("qty", extra).put("done", false)
                val r = runCatching { SupabaseHub.insertRow(Tables.SHOPPING, row) }.getOrNull()
                if (r == null || !r.ok) queued(Tables.SHOPPING, row, "zakupy: $title")
                else ToolResult.ok("Na listę zakupów: $title")
            }
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
            if (title.isBlank()) ToolResult.fail("Podaj imię.")
            else {
                val row = JSONObject().put("name", title.trim()).put("relation", extra).put("notes", "")
                val r = runCatching { SupabaseHub.insertRow(Tables.PEOPLE, row) }.getOrNull()
                if (r == null || !r.ok) queued(Tables.PEOPLE, row, "osoba: $title")
                else ToolResult.ok("Zapamiętałam osobę: $title")
            }
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

    private fun queued(table: String, row: JSONObject, what: String): ToolResult {
        OfflineQueue.enqueue(
            ToolContext.app, OfflineQueue.TYPE_ROW,
            JSONObject().put("table", table).put("row", row),
        )
        return ToolResult.ok("Brak sieci — $what czeka w kolejce i dojdzie samo.")
    }

    private suspend fun lessons(): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Brak bazy — nie odczytam planu lekcji.")
        val cal = java.util.Calendar.getInstance()
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val iso = if (dow == java.util.Calendar.SUNDAY) 7 else dow - 1
        val r = SupabaseHub.listRows(
            Tables.LESSONS,
            filters = mapOf("day_of_week" to "eq.$iso"),
            orderBy = "start.asc",
            limit = 20,
        )
        if (!r.ok) return ToolResult.fail(r.error ?: "Brak planu lekcji.")
        if (r.rows.isEmpty()) return ToolResult.ok("Dziś nie mam lekcji w planie.")
        val names = arrayOf("", "poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela")
        val day = names.getOrElse(iso) { "dzień $iso" }
        return ToolResult.ok(
            "Plan ($day):\n" + r.rows.joinToString("\n") { o ->
                "${o.optString("start")} ${o.optString("subject")} ${o.optString("room")} ${o.optString("teacher")}"
            }
        )
    }

    fun dumpBackup(): ToolResult {
        val snap = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("report", dev.nixi.util.ErrorReport.snapshot())
            .put("queue", OfflineQueue.size(ToolContext.app))
        val name = "nixi-backup.json"
        val ctx = ToolContext.app
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return ToolResult.fail("Nie mogę utworzyć pliku w Pobranych.")
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(snap.toString(2).toByteArray()) }
                    ?: return ToolResult.fail("Nie mogę zapisać kopii.")
            } else {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                dir.mkdirs()
                java.io.File(dir, name).writeText(snap.toString(2))
            }
            ToolResult.ok("Kopia w Pobranych: $name")
        } catch (t: Throwable) {
            ToolResult.fail("Backup: ${t.message}")
        }
    }
}
