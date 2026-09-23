package dev.nixi.tools

import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.notif.ActionNotifier
import dev.nixi.util.TimeUtils
import org.json.JSONObject

/**
 * Wbudowany kalendarz NIXI — tabela calendar_events w Supabase
 * (żaden kalendarz Google/systemowy).
 * Kolumny: id, title, start, end, location, notes, kind
 *   kind: normal | zastepstwo | wolne
 */
object CalendarTools {

    suspend fun list(range: String): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny.")
        val (t0, t1) = when (range.lowercase()) {
            "dziś", "today" -> TimeUtils.todayWindow()
            "jutro", "tomorrow" -> TimeUtils.windowFor(1)
            "tydzień", "week", "" -> {
                val c = java.util.Calendar.getInstance()
                val end = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_MONTH, 7) }
                val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                f.timeZone = java.util.TimeZone.getDefault()
                f.format(c.time) to f.format(end.time)
            }
            else -> {
                val parsed = TimeUtils.parseFlexible(range) ?: return ToolResult.fail(
                    "Nie rozumiem zakresu „$range” (użyj: dziś, jutro, tydzień, 2026-09-25)."
                )
                parsed to (TimeUtils.parseFlexible(
                    java.util.Date(java.text.SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US
                    ).parse(parsed)!!.time + 86_400_000L) ?: parsed) ?: parsed)
            }
        }
        val events = SupabaseHub.calendarEventsBetween(t0, t1)
        if (events.isEmpty()) return ToolResult.ok("Brak wydarzeń w tym zakresie.")
        val list = events.take(15).joinToString("\n") { e ->
            buildString {
                append("- ").append(TimeUtils.dateOf(e.optString("start"))).append(" ")
                    .append(TimeUtils.hourOf(e.optString("start")))
                append("–").append(TimeUtils.hourOf(e.optString("end")))
                append(" ").append(e.optString("title"))
                val loc = e.optString("location")
                if (loc.isNotBlank()) append(" @").append(loc)
                if (e.optString("kind") == "zastepstwo") append(" [ZASTĘPSTWO]")
                if (e.optString("kind") == "wolne") append(" [WOLNE]")
            }
        }
        return ToolResult.ok("Wydarzenia:\n$list")
    }

    suspend fun add(title: String, start: String, end: String, location: String, notes: String): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny.")
        val s = TimeUtils.parseFlexible(start) ?: return ToolResult.fail(
            "Nie rozumiem godziny/początku „$start” (np. 15:30 albo 2026-09-25T10:00)."
        )
        val e = if (end.isNotBlank()) TimeUtils.parseFlexible(end) ?: s else s
        val row = JSONObject().apply {
            put("title", title)
            put("start", s)
            put("end", e)
            put("location", location)
            put("notes", notes)
            put("kind", "normal")
        }
        val r = runCatching { SupabaseHub.insertRow(Tables.CALENDAR, row) }.getOrNull()
            ?: return ToolResult.fail("Błąd zapisu.")
        if (!r.ok) return ToolResult.fail("Błąd zapisu: ${r.error}")
        ActionNotifier.notify(
            ToolContext.app, "NIXI: kalendarz",
            "Nowe wydarzenie: $title (${TimeUtils.dateOf(s)} ${TimeUtils.hourOf(s)})", short = true
        )
        return ToolResult.ok(
            "Dodałam: $title, ${TimeUtils.dateOf(s)} ${TimeUtils.hourOf(s)}${if (end.isNotBlank()) " do " + TimeUtils.hourOf(e) else ""}."
        )
    }

    suspend fun update(id: String, title: String, start: String, end: String,
                       location: String, notes: String, kind: String): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny.")
        if (id.isBlank()) return ToolResult.fail("Podaj id wydarzenia.")
        val row = JSONObject()
        title.takeIf { it.isNotBlank() }?.let { row.put("title", it) }
        start.takeIf { it.isNotBlank() }?.let { row.put("start", TimeUtils.parseFlexible(it)) }
        end.takeIf { it.isNotBlank() }?.let { row.put("end", TimeUtils.parseFlexible(it)) }
        location.takeIf { it.isNotBlank() }?.let { row.put("location", it) }
        notes.takeIf { it.isNotBlank() }?.let { row.put("notes", it) }
        kind.takeIf { it.isNotBlank() }?.let { row.put("kind", it) }
        if (row.length() == 0) return ToolResult.fail("Brak pól do edycji.")
        val r = runCatching {
            SupabaseHub.updateRow(Tables.CALENDAR, mapOf("id" to "eq.$id"), row)
        }.getOrNull() ?: return ToolResult.fail("Błąd edycji.")
        if (!r.ok) return ToolResult.fail("Błąd edycji: ${r.error}")
        ActionNotifier.notify(ToolContext.app, "NIXI: kalendarz", "Edycja wydarzenia #$id.", short = true)
        return ToolResult.ok("Zaktualizowałam wydarzenie #$id.")
    }

    suspend fun remove(id: String): ToolResult {
        if (id.isBlank()) return ToolResult.fail("Podaj id wydarzenia.")
        val r = runCatching {
            SupabaseHub.deleteRows(Tables.CALENDAR, mapOf("id" to "eq.$id"))
        }.getOrNull() ?: return ToolResult.fail("Błąd usuwania.")
        if (!r.ok) return ToolResult.fail("Błąd usuwania: ${r.error}")
        ActionNotifier.notify(ToolContext.app, "NIXI: kalendarz", "Usunięto wydarzenie #$id.", short = true)
        return ToolResult.ok("Usunęłam wydarzenie #$id.")
    }

    suspend fun substitute(subject: String): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny.")
        val (t0, t1) = TimeUtils.todayWindow()
        val events = SupabaseHub.calendarEventsBetween(t0, t1)
        var changed = 0
        var lastTitle = ""
        for (e in events) {
            val eTitle = e.optString("title").lowercase()
            if (e.optString("kind") == "zastepstwo") continue
            val subj = subject.lowercase()
            if (subj.isNotEmpty() && (eTitle.contains(subj) || subj.contains(eTitle))) {
                val row = JSONObject().apply {
                    put("kind", "zastepstwo")
                    put("title", "Zastępstwo: $subject")
                }
                val r = SupabaseHub.updateRow(Tables.CALENDAR, mapOf("id" to "eq.${e.getInt("id")}"), row)
                if (r.ok) { changed++; lastTitle = e.optString("title") }
            }
        }
        if (changed == 0) return ToolResult.ok("Nie znalazłam pasującego wydarzenia na dziś.")
        ActionNotifier.notify(
            ToolContext.app, "NIXI: kalendarz",
            "Zastępstwo: $subject (zmieniono: $changed)", short = true
        )
        return ToolResult.ok("Oznaaczyłam zastępstwo: $subject (poprzednio: $lastTitle).")
    }
}
