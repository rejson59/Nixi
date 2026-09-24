package dev.nixi.live

import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.store.LocalStore
import dev.nixi.util.LogBus
import dev.nixi.util.TimeUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Składa systemInstruction dla sesji Live:
 *  persona (admin_table) + profil użytkownika + fakty (pamięć długotrwała)
 *  + ostatnie rozmowy + kalendarz dziś + lekcje + budziki + rutyny.
 *
 * WAŻNE (szybkość): ten kod stoi między „Hej Nixi" a pierwszą sylabą NIXI,
 * dlatego:
 *  - część statyczna (persona, język, profil) jest cache'owana lokalnie na 24 h
 *    i odświeżana w tle — start nie czeka na sieć,
 *  - zapytania o kontekst lecą RÓWNOLEGLE (wcześniej ~10 zapytań szło po kolei,
 *    więc start potrafił trwać kilka sekund),
 *  - każde zapytanie ma własny limit czasu, więc jeden wolny stół nie zabiera
 *    całego kontekstu.
 */
object SystemPromptBuilder {

    /** Limit na pojedyncze zapytanie o kontekst (kalendarz, lekcje, budziki...). */
    private const val CONTEXT_TIMEOUT_MS = 2000L

    /** Jak długo część statyczna promptu jest uznawana za świeżą. */
    private const val STATIC_TTL_MS = 24 * 60 * 60 * 1000L

    suspend fun build(screenWidth: Int = 0, screenHeight: Int = 0): String {
        val started = System.currentTimeMillis()
        val sb = StringBuilder(staticPart())
        sb.append("Teraz: ").append(TimeUtils.fullNow()).append("\n")

        val context = dynamicPart()
        if (context.isNotBlank()) sb.append(context)

        if (screenWidth > 0 && screenHeight > 0) {
            sb.append("\nAKTYWNY TRYB RĘCZNY: widać ekran telefonu o rozdzielczości ")
                .append(screenWidth).append("x").append(screenHeight)
                .append(". Współrzędne dla screen_tap/screen_swipe podawaj w pikselach tego ekranu.")
        }

        LogBus.log(
            "prompt.build",
            "gotowy w ${System.currentTimeMillis() - started} ms (${sb.length} znaków)"
        )
        return sb.toString().take(12000)
    }

    // ── Część statyczna (z cache) ───────────────────────────────────────────

    private suspend fun staticPart(): String {
        val cached = LocalStore.promptStatic
        val age = System.currentTimeMillis() - LocalStore.promptStaticAt
        if (cached.isNotBlank() && age < STATIC_TTL_MS) {
            // nie blokujemy sesji — odświeżymy cache na następny raz
            dev.nixi.NixiApp.scope.launch { runCatching { refreshStaticCache() } }
            return cached
        }
        // pierwszy raz (albo cache przeterminowany) — czekamy krótko,
        // a jeśli sieć nie zdąży, ratujemy się starą wersją
        val fresh = withTimeoutOrNull(if (cached.isBlank()) 2500L else 1500L) { fetchStatic() }
        if (!fresh.isNullOrBlank()) {
            LocalStore.promptStatic = fresh
            LocalStore.promptStaticAt = System.currentTimeMillis()
            return fresh
        }
        LogBus.log("prompt.build", "persona z sieci nie zdążyła — używam zapisanej", "warn")
        return cached.ifBlank { defaultStatic() }
    }

    private suspend fun refreshStaticCache() {
        val fresh = withTimeoutOrNull(4000) { fetchStatic() } ?: return
        if (fresh.isNotBlank()) {
            LocalStore.promptStatic = fresh
            LocalStore.promptStaticAt = System.currentTimeMillis()
        }
    }

    private fun defaultStatic(): String =
        Tables.DEFAULT_SYSTEM_PROMPT + Tables.EXECUTION_RULES +
            "\nJesteś NIXI, osobistą asystentką. Mów po polsku, zwięźle.\n"

    private suspend fun fetchStatic(): String {
        val sb = StringBuilder()
        runCatching { SupabaseHub.loadAdmin() }

        sb.append(SupabaseHub.admin("system_prompt", Tables.DEFAULT_SYSTEM_PROMPT)).append("\n")
        sb.append(Tables.EXECUTION_RULES).append("\n")
        val language = SupabaseHub.admin("language", "polski")
        val name = SupabaseHub.admin("assistant_name", "NIXI")
        sb.append("Język domyślny: $language. Twoje imię: $name.\n")

        if (SupabaseHub.available) {
            val userRow = runCatching {
                SupabaseHub.c().listRows(Tables.USER, limit = 1).rows.firstOrNull()
            }.getOrNull()
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
        }
        return sb.toString()
    }

    // ── Kontekst dnia (równolegle) ─────────────────────────────────────────

    private suspend fun dynamicPart(): String {
        if (!SupabaseHub.available) return ""

        val (t0, t1) = TimeUtils.todayWindow()
        val dow = TimeUtils.dayOfWeekPostgres()

        return coroutineScope {
            val facts = async { safe { SupabaseHub.loadFacts(20) } }
            val recent = async { safe { SupabaseHub.recentConversations(3) } }
            val events = async { safe { SupabaseHub.calendarEventsBetween(t0, t1) } }
            val lessons = async { safe { SupabaseHub.todayLessons(dow) } }
            val alarms = async {
                safe { SupabaseHub.c().listRows(Tables.ALARMS, mapOf("active" to "eq.true"), limit = 20).rows }
            }
            val routines = async {
                safe { SupabaseHub.c().listRows(Tables.ROUTINES, mapOf("active" to "eq.true"), limit = 20).rows }
            }

            val sb = StringBuilder()
            try {
                appendFacts(sb, facts.await())
                appendRecent(sb, recent.await())
                appendCalendar(sb, events.await())
                appendLessons(sb, lessons.await())
                appendAlarms(sb, alarms.await())
                appendRoutines(sb, routines.await())
            } catch (t: Throwable) {
                sb.append("(kontekst z Supabase niepełny: ").append(t.message).append(")\n")
            }
            sb.toString()
        }
    }

    /** Zapytanie z limitem czasu — wolny stół nie zabiera całego kontekstu. */
    private suspend fun <T> safe(block: suspend () -> T): T? =
        runCatching { withTimeoutOrNull(CONTEXT_TIMEOUT_MS) { block() } }.getOrNull()

    private fun appendFacts(sb: StringBuilder, facts: List<JSONObject>?) {
        if (facts.isNullOrEmpty()) return
        sb.append("Trwałe fakty o użytkowniku:\n")
        for (f in facts) {
            sb.append("- [").append(f.optString("category", "fakt")).append("] ")
                .append(f.optString("value")).append("\n")
        }
    }

    private fun appendRecent(sb: StringBuilder, recent: List<JSONObject>?) {
        if (recent.isNullOrEmpty()) return
        sb.append("Ostatnie ciekawe rozmowy (kontekst):\n")
        for (r in recent) {
            sb.append("- ").append(r.optString("summary").take(220)).append("\n")
        }
    }

    private fun appendCalendar(sb: StringBuilder, events: List<JSONObject>?) {
        if (events.isNullOrEmpty()) return
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

    private fun appendLessons(sb: StringBuilder, lessons: List<JSONObject>?) {
        if (lessons.isNullOrEmpty()) return
        sb.append("Lekcje dziś:\n")
        for (l in lessons) {
            sb.append("- ").append(l.optString("start")).append("-").append(l.optString("end"))
                .append(" ").append(l.optString("subject"))
            val room = l.optString("room")
            if (room.isNotBlank()) sb.append(" s. ").append(room)
            sb.append("\n")
        }
    }

    private fun appendAlarms(sb: StringBuilder, alarms: List<JSONObject>?) {
        if (alarms.isNullOrEmpty()) return
        sb.append("Ustawione budziki: ")
        sb.append(alarms.joinToString(", ") {
            it.optString("time") + (it.optString("label").ifBlank { "" })
        })
        sb.append("\n")
    }

    private fun appendRoutines(sb: StringBuilder, routines: List<JSONObject>?) {
        if (routines.isNullOrEmpty()) return
        sb.append("Rutyny (wykonuj, gdy padnie trigger):\n")
        for (r in routines) {
            sb.append("- „").append(r.optString("trigger"))
                .append("” => ").append(r.optString("steps")).append("\n")
        }
    }
}
