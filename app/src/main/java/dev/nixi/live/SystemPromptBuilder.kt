package dev.nixi.live

import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.util.TimeUtils
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Składa systemInstruction dla sesji Live:
 *  persona (admin_table) + profil użytkownika + fakty (pamięć długotrwała)
 *  + ostatnie rozmowy + kalendarz dziś + lekcje + budziki + rutyny.
 */
object SystemPromptBuilder {

    suspend fun build(screenWidth: Int = 0, screenHeight: Int = 0): String {
        val base = withTimeoutOrNull(2500) { SupabaseHub.loadAdmin() }
        val persona = SupabaseHub.admin("system_prompt", Tables.DEFAULT_SYSTEM_PROMPT)
        val language = SupabaseHub.admin("language", "polski")
        val name = SupabaseHub.admin("assistant_name", "NIXI")

        val sb = StringBuilder()
        sb.append(persona).append("\n\n")
        sb.append("Język domyślny: $language. Twoje imię: $name.\n")
        sb.append("Teraz: ").append(TimeUtils.fullNow()).append("\n")

        try {
            // profil użytkownika (pierwszy wiersz users)
            if (SupabaseHub.available) {
                val userRow = SupabaseHub.c().listRows(Tables.USER, limit = 1).rows.firstOrNull()
                if (userRow != null) {
                    val uname = userRow.optString("display_name").ifBlank {
                        userRow.optString("name")
                    }
                    sb.append("Użytkownik: ")
                        .append(if (uname.isBlank()) "brak imienia" else uname)
                    val notes = userRow.optString("notes").ifBlank { userRow.optString("schedule_notes") }
                    if (notes.isNotBlank()) sb.append(" (notatki: ").append(notes.take(400)).append(")")
                    sb.append("\n")
                }

                // fakty
                val facts = SupabaseHub.loadFacts(20)
                if (facts.isNotEmpty()) {
                    sb.append("Trwałe fakty o użytkowniku:\n")
                    for (f in facts) {
                        sb.append("- [").append(f.optString("category", "fakt")).append("] ")
                            .append(f.optString("value")).append("\n")
                    }
                }

                // ostatnie ciekawe rozmowy
                val recent = SupabaseHub.recentConversations(3)
                if (recent.isNotEmpty()) {
                    sb.append("Ostatnie ciekawe rozmowy (kontekst):\n")
                    for (r in recent) {
                        sb.append("- ").append(r.optString("summary").take(220)).append("\n")
                    }
                }

                // kalendarz dziś
                val (t0, t1) = TimeUtils.todayWindow()
                val events = SupabaseHub.calendarEventsBetween(t0, t1)
                if (events.isNotEmpty()) {
                    sb.append("Kalendarz na dziś:\n")
                    for (e in events.take(12)) {
                        sb.append("- ").append(TimeUtils.hourOf(e.optString("start")))
                            .append(" ").append(e.optString("title"))
                            val loc = e.optString("location")
                            if (loc.isNotBlank()) sb.append(" (").append(loc).append(")")
                            if (e.optString("kind") == "zastepstwo") sb.append(" — ZASTĘPSTWO")
                            sb.append("\n")
                    }
                }

                // lekcje dziś
                val lessons = SupabaseHub.todayLessons(TimeUtils.dayOfWeekPostgres())
                if (lessons.isNotEmpty()) {
                    sb.append("Lekcje dziś:\n")
                    for (l in lessons) {
                        sb.append("- ").append(l.optString("start")).append("-").append(l.optString("end"))
                            .append(" ").append(l.optString("subject"))
                            val room = l.optString("room")
                            if (room.isNotBlank()) sb.append(" s. ").append(room)
                            sb.append("\n")
                    }
                }

                // budziki (aktywne)
                val alarms = SupabaseHub.c().listRows(
                    Tables.ALARMS, mapOf("active" to "eq.true"), limit = 20
                ).rows
                if (alarms.isNotEmpty()) {
                    sb.append("Ustawione budziki: ")
                    sb.append(alarms.joinToString(", ") {
                        it.optString("time") + (it.optString("label").ifBlank { "" })
                    })
                    sb.append("\n")
                }

                // rutyny
                val routines = SupabaseHub.c().listRows(
                    Tables.ROUTINES, mapOf("active" to "eq.true"), limit = 20
                ).rows
                if (routines.isNotEmpty()) {
                    sb.append("Rutyny (wykonuj, gdy padnie trigger):\n")
                    for (r in routines) {
                        sb.append("- „").append(r.optString("trigger"))
                            .append("” => ").append(r.optString("steps")).append("\n")
                    }
                }
            }
        } catch (t: Throwable) {
            sb.append("(kontekst z Supabase niedostępny: ").append(t.message).append(")\n")
        }

        if (screenWidth > 0 && screenHeight > 0) {
            sb.append("\nAKTYWNY TRYB RĘCZNY: widać ekran telefonu o rozdzielczości ")
                .append(screenWidth).append("x").append(screenHeight)
                .append(". Współrzędne dla screen_tap/screen_swipe podawaj w pikselach tego ekranu.")
        }

        return sb.toString().take(12000)
    }
}
