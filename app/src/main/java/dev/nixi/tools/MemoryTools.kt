package dev.nixi.tools

import dev.nixi.db.SupabaseHub
import dev.nixi.notif.ActionNotifier
import dev.nixi.util.LogBus

/** Pamięć długotrwała (tabela memory_facts). */
object MemoryTools {

    suspend fun store(fact: String, category: String): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny — nie mogę zapisać.")
        val key = sanitize(fact)
        SupabaseHub.upsertFact(key, fact.trim(), category.ifBlank { "ogólne" })
        LogBus.log("memory.store", fact.take(80))
        ActionNotifier.notify(
            ToolContext.app, "NIXI: pamięć",
            "Zapamiętałam: ${fact.take(90)}", short = true
        )
        return ToolResult.ok("Zapisałam do pamięci długotrwałej.")
    }

    suspend fun recall(query: String): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny.")
        val facts = SupabaseHub.loadFacts(60)
        if (facts.isEmpty()) return ToolResult.ok("Nie mam jeszcze zapamiętanych faktów.")
        val words = query.lowercase().split(Regex("[^a-ząćęłńóśźż0-9]+")).filter { it.length > 2 }
        val scored = facts.map { f ->
            val text = (f.optString("value") + " " + f.optString("key")).lowercase()
            val score = words.count { text.contains(it) }
            score to f
        }.filter { it.first > 0 }
            .sortedByDescending { it.first }
            .take(6)
        if (scored.isEmpty()) return ToolResult.ok("Nie znalazłam trafień dla „$query”.")
        val list = scored.joinToString("\n") {
            "- [${it.second.optString("category")}] ${it.second.optString("value")}"
        }
        return ToolResult.ok("Trafienia w pamięci:\n$list")
    }

    suspend fun forget(what: String): ToolResult {
        if (!SupabaseHub.available) return ToolResult.fail("Supabase niedostępny.")
        val key = sanitize(what)
        val ok = SupabaseHub.deleteFact(key)
        return if (ok) ToolResult.ok("Usunęłam z pamięci.")
        else ToolResult.fail("Nie znalazłam takiego faktu do usunięcia.")
    }

    private fun sanitize(s: String): String {
        return s.trim().lowercase()
            .replace(Regex("[^a-ząćęłńóśźż0-9]+"), "_")
            .trim('_')
            .take(60)
    }
}
