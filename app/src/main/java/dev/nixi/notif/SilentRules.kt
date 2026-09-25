package dev.nixi.notif

import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.util.LogBus
import org.json.JSONObject

/** Ciche reguły z powiadomień i SMS (eduVulcan, dziennik, itd.). */
object SilentRules {

    suspend fun apply(rule: JSONObject, pkg: String, title: String, text: String) {
        val action = rule.optJSONObject("action") ?: return
        val type = action.optString("type")
        val app = dev.nixi.NixiApp.ctx()
        when (type) {
            "calendar_substitution" -> markToday(
                pkg, title, text, action,
                kind = "zastepstwo",
                titlePrefix = "Zastępstwo",
                extract = Regex("""zastępstw[oa]\s*[:\-–]?\s*(.+)""", RegexOption.IGNORE_CASE),
            )
            "calendar_test", "calendar_exam" -> markToday(
                pkg, title, text, action,
                kind = "sprawdzian",
                titlePrefix = "Sprawdzian",
                extract = Regex("""sprawdzian\s*[:\-–]?\s*(.+)""", RegexOption.IGNORE_CASE),
            )
            "calendar_day_off" -> markToday(
                pkg, title, text, action,
                kind = "wolne",
                titlePrefix = "Wolne",
                extract = Regex("""wolne\s*[:\-–]?\s*(.+)""", RegexOption.IGNORE_CASE),
            )
            "reminder" -> {
                val whenTxt = action.optString("when", "za 30 min")
                val titleR = action.optString("title").ifBlank { title.ifBlank { text.take(80) } }
                if (titleR.isBlank()) return
                val r = dev.nixi.tools.ReminderTools.add(titleR, whenTxt)
                if (r.ok) {
                    ActionNotifier.notify(app, "NIXI: cicha akcja", r.text, short = true)
                    LogBus.log("rule.reminder", titleR)
                }
            }
            "memory" -> {
                val fact = action.optString("fact").ifBlank { "$title $text".trim() }
                if (fact.isBlank()) return
                val r = dev.nixi.tools.MemoryTools.store(fact, action.optString("category", "powiadomienia"))
                if (r.ok) LogBus.log("rule.memory", fact.take(80))
            }
            else -> LogBus.log("rule.unknown", "typ: $type", "warn")
        }
    }

    private suspend fun markToday(
        pkg: String, title: String, text: String, action: JSONObject,
        kind: String, titlePrefix: String, extract: Regex,
    ) {
        val body = "$title $text"
        val extracted = extract.find(body)?.groupValues?.get(1)?.trim()?.take(80)
        val subject = action.optString("subject").ifBlank {
            extracted ?: text.trim().ifBlank { title.trim() }
        }
        if (subject.isBlank()) return
        val (isoStart, isoEnd) = dev.nixi.util.TimeUtils.todayWindow()
        val events = SupabaseHub.calendarEventsBetween(isoStart, isoEnd)
        var changed = 0
        for (e in events) {
            val eTitle = e.optString("title").lowercase()
            if (e.optString("kind", "normal") == kind) continue
            val subj = subject.lowercase()
            val match = subj.isNotEmpty() &&
                (eTitle.contains(subj) || subj.contains(eTitle) ||
                    (subj.length > 3 && eTitle.contains(subj.take(6))))
            if (!match) continue
            val row = JSONObject().apply {
                put("kind", kind)
                put("title", "$titlePrefix: $subject")
                put("notes", (e.optString("notes", "") + " [auto NIXI: $text]").trim())
            }
            val id = e.opt("id")?.toString() ?: continue
            if (id.isBlank()) continue
            val r = SupabaseHub.updateRow(Tables.CALENDAR, mapOf("id" to "eq.$id"), row)
            if (r.ok) changed++
        }
        if (changed > 0) {
            ActionNotifier.notify(
                dev.nixi.NixiApp.ctx(), "NIXI: cicha akcja",
                "$titlePrefix („$subject”) — kalendarz ($changed).",
                short = true,
            )
            LogBus.log("rule.$kind", "$pkg → $subject ($changed)")
        }
    }
}
