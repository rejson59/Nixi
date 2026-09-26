package dev.nixi.live

import dev.nixi.db.SupabaseHub
import dev.nixi.store.LocalStore
import dev.nixi.tools.AlarmTools
import dev.nixi.tools.CalendarTools
import dev.nixi.tools.MemoryTools
import dev.nixi.tools.NotificationTools
import dev.nixi.tools.ScreenTools
import dev.nixi.tools.SpotifyApi
import dev.nixi.tools.SpotifyTools
import dev.nixi.tools.SupabaseTools
import dev.nixi.tools.SystemTools
import dev.nixi.NixiState
import dev.nixi.tools.LifeTools
import dev.nixi.tools.LookupTools
import dev.nixi.tools.PhoneTools
import dev.nixi.tools.ReminderTools
import dev.nixi.tools.ToolContext
import dev.nixi.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rejestr narzędzi NIXI: deklaracje (functionDeclarations) + dispatch.
 * Narzędzia DESTRUKTYWNE przechodzą przez bramkę potwierdzenia użytkownika
 * (okno w oknie rozmowy) — patrz LiveSessionService.
 */
object ToolRegistry {

    val DANGEROUS = setOf(
        "db_delete", "alarm_remove", "calendar_remove", "reminder_remove", "rule_remove",
    )

    // ── Deklaracje ─────────────────────────────────────────────────────────

    fun declarations(): JSONArray {
        val arr = JSONArray()
        fun add(name: String, desc: String, props: Map<String, JSONObject>, required: List<String>) {
            val p = JSONObject().put("type", "object")
                .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            if (required.isNotEmpty()) p.put("required", JSONArray().apply { required.forEach { put(it) } })
            arr.put(JSONObject().put("name", name).put("description", desc).put("parameters", p))
        }
        fun s(desc: String) = JSONObject().put("type", "string").put("description", desc)
        fun i(desc: String) = JSONObject().put("type", "integer").put("description", desc)
        fun obj(desc: String) = JSONObject().put("type", "object").put("description", desc)

        // system
        add("system_now", "Aktualna data, godzina, bateria i internet.", emptyMap(), emptyList())
        add("media_pause", "Zatrzymaj odtwarzanie muzyki/multimediów na telefonie.", emptyMap(), emptyList())
        add("media_resume", "Wznów odtwarzanie muzyki/multimediów.", emptyMap(), emptyList())
        add("open_app", "Otwórz aplikację telefonu po nazwie.", mapOf("app" to s("nazwa aplikacji")), listOf("app"))
        add(
            "phone",
            "Sterowanie telefonem. Akcje: status, volume, brightness, torch, timer, web, maps, clipboard, dial, call, sms, share, ringer, screenshot, lock, apps, settings, vibrate, find, contacts, inbox, location, wifi, dnd, backup, report, diary.",
            mapOf(
                "action" to s("status|volume|brightness|torch|timer|web|maps|clipboard|dial|call|sms|share|ringer|screenshot|lock|apps|settings|vibrate|find|contacts|inbox|location|wifi|dnd|backup|report|diary|next|prev"),
                "value" to s("np. 50, mute, 5 min, numer, zapytanie, on/off"),
                "extra" to s("opcjonalnie: strumień (music/ring), treść SMS, etykieta minutnika"),
            ),
            listOf("action"),
        )
        add(
            "lookup",
            "Sama znajdź odpowiedź (pogoda, Wikipedia, liczenie, tłumaczenie). NIE otwieraj przeglądarki.",
            mapOf("query" to s("pytanie, miasto, wyrażenie")),
            listOf("query"),
        )
        add("user_profile", "Pokaż profil użytkownika (tabela users).", emptyMap(), emptyList())
        add("user_profile_update", "Zaktualizuj profil użytkownika (np. notes, display_name).",
            mapOf("fields" to obj("pola do zmiany")), listOf("fields"))

        // pamięć
        add("memory_store", "Zapisz trwałą informację o użytkowniku do pamięci długotrwałej.",
            mapOf("fact" to s("fakt do zapamiętania"), "category" to s("np. preferencje, praca, szkoła")),
            listOf("fact"))
        add("memory_recall", "Wyszukaj zapamiętane fakty.", mapOf("query" to s("słowa kluczowe")), listOf("query"))
        add("memory_forget", "Usuń zapamiętany fakt.", mapOf("fact" to s("fakt do usunięcia")), listOf("fact"))

        // supabase
        add("db_list_tables", "Lista wszystkich tabel w Supabase (auto-odkrywanie).", emptyMap(), emptyList())
        add("db_describe", "Kolumny i liczbę wierszy tabeli.", mapOf("table" to s("nazwa tabeli")), listOf("table"))
        add("db_query", "Odczytaj wiersze tabeli (opcjonalnie proste filtry równości).",
            mapOf("table" to s("nazwa tabeli"), "filters" to obj("np. {\"kind\":\"zastepstwo\"}"), "limit" to i("domyślnie 20")),
            listOf("table"))
        add("db_insert", "Dodaj nowy wiersz do tabeli.",
            mapOf("table" to s("nazwa tabeli"), "row" to obj("wiersz JSON")), listOf("table", "row"))
        add("db_update", "Edytuj wiersz tabeli po id.",
            mapOf("table" to s("nazwa tabeli"), "id" to s("id wiersza"), "row" to obj("pole: nowa wartość")),
            listOf("table", "id", "row"))
        add("db_delete", "USUŃ wiersz tabeli (wymaga potwierdzenia użytkownika).",
            mapOf("table" to s("nazwa tabeli"), "id" to s("id wiersza")), listOf("table", "id"))
        add("db_ddl", "Zaproponuj SQL dla nowej tabeli/kolumny. NIXI NIE uruchamia SQL — pokazuje go użytkownikowi do wklejenia w SQL Editorze.",
            mapOf("sql" to s("pojedyncze zapytanie CREATE/ALTER")), listOf("sql"))

        // kalendarz
        add("calendar_list", "Wyświetl wydarzenia kalendarza NIXI (dziś, jutro, pojutrze, tydzień, dalej = następne, albo data).",
            mapOf("range" to s("np. dziś, jutro, pojutrze, tydzień, dalej, 2026-09-25")), emptyList())
        add("calendar_add", "Dodaj wydarzenie do kalendarza NIXI.",
            mapOf("title" to s("tytuł"), "start" to s("np. 15:30 albo 2026-09-25T15:30"),
                "end" to s("koniec (opcjonalnie)"), "location" to s("miejsce (opcjonalnie)"),
                "notes" to s("notatki (opcjonalnie)"), "kind" to s("normal|zastepstwo|wolne|sprawdzian")),
            listOf("title", "start"))
        add("calendar_update", "Edytuj wydarzenie kalendarza po id.",
            mapOf("id" to s("id wydarzenia"), "title" to s(""), "start" to s(""), "end" to s(""),
                "location" to s(""), "notes" to s(""), "kind" to s("normal|zastepstwo|wolne|sprawdzian")), listOf("id"))
        add("calendar_remove", "USUŃ wydarzenie kalendarza (wymaga potwierdzenia).",
            mapOf("id" to s("id wydarzenia")), listOf("id"))
        add("calendar_substitute", "Oznacz wydarzenia pasujące do przedmiotu jako ZASTĘPSTWO.",
            mapOf("subject" to s("np. Matematyka")), listOf("subject"))
        add("calendar_mark", "Oznacz wydarzenie na dziś: wolne / sprawdzian / zastępstwo.",
            mapOf("kind" to s("wolne|sprawdzian|zastepstwo"), "subject" to s("przedmiot albo tytuł")),
            listOf("kind", "subject"))

        // budziki
        add("alarm_list", "Lista ustawionych budzików.", emptyMap(), emptyList())
        add("alarm_add", "Ustaw systemowy budzik.",
            mapOf("time" to s("HH:mm, jutro 7:00, za 20 minut, pojutrze 7:00"), "label" to s("etykieta (opcjonalnie)"),
                "days" to s("daily|weekdays|weekend (opcjonalnie)"), "sound" to s("dźwięk (opcjonalnie)")),
            listOf("time"))
        add("alarm_edit", "Edytuj budzik po id (czas/etykieta/dźwięk/aktywność).",
            mapOf("id" to s("id"), "time" to s(""), "label" to s(""), "sound" to s(""),
                "active" to s("true/false")), listOf("id"))
        add("alarm_remove", "USUŃ/wyłącz budzik (wymaga potwierdzenia).", mapOf("id" to s("id")), listOf("id"))

        // przypomnienia
        add("reminder_list", "Lista przypomnień.", emptyMap(), emptyList())
        add("reminder_add", "Ustaw przypomnienie.",
            mapOf("title" to s("treść"), "when" to s("np. 17:30, jutro 8:00, 2026-09-25 17:30")),
            listOf("title", "when"))
        add("reminder_done", "Oznacz przypomnienie jako wykonane.", mapOf("id" to s("id")), listOf("id"))
        add("reminder_remove", "USUŃ przypomnienie (wymaga potwierdzenia).", mapOf("id" to s("id")), listOf("id"))

        // powiadomienia
        add("notifications_read", "Odczytaj nowe powiadomienia telefonu (wymaga dostępu do powiadomień).",
            mapOf("app" to s("filtr pakietu aplikacji (opcjonalnie)"), "limit" to i("domyślnie 10")), emptyList())

        // spotify
        add("spotify_status", "Czy Spotify jest połączone.", emptyMap(), emptyList())
        add("spotify_connect", "Uruchom parowanie Spotify (klient podaje Client ID).",
            mapOf("client_id" to s("Client ID z developer.spotify.com")), listOf("client_id"))
        add("spotify_now_playing", "Co teraz gra w Spotify.", emptyMap(), emptyList())
        add("spotify_pause", "Zatrzymaj Spotify.", emptyMap(), emptyList())
        add("spotify_resume", "Wznów Spotify.", emptyMap(), emptyList())
        add("spotify_next", "Następny utwór w Spotify.", emptyMap(), emptyList())
        add("spotify_previous", "Poprzedni utwór w Spotify.", emptyMap(), emptyList())
        add("spotify_seek", "Przewiń w Spotify.", mapOf("position_sec" to i("sekundy")), listOf("position_sec"))
        add("spotify_volume", "Głośność Spotify.", mapOf("percent" to i("0-100")), listOf("percent"))
        add("spotify_play", "Odtwórz w Spotify.",
            mapOf("query" to s("nazwa"), "type" to s("track|playlist|album")), listOf("query"))
        add("spotify_list_playlists", "Lista playlist użytkownika.", mapOf("limit" to i("domyślnie 15")), emptyList())

        // tryb ręczny (ekran)
        add("screen_manual_start", "Włącz tryb ręczny: NIXI widzi ekran i może na nim klikać. Użyj gdy nie ma narzędzia do zadania albo użytkownik prosi o przejęcie ekranu. Jeśli brakuje zgody, poproś o nią i powtórz po zgodzie.",
            emptyMap(), emptyList())
        add("screen_manual_stop", "Wyłącz tryb ręczny (po zakończeniu zadania).", emptyMap(), emptyList())
        add("screen_get", "Pobierz zrzut ekranu (zobacz gdzie kliknąć).", emptyMap(), emptyList())
        add("screen_tap", "Kliknij punkt ekranu. Podawaj PIKSELE; jeśli podasz obie " +
            "współrzędne w zakresie 0..100, potraktuję je jako procenty ekranu.",
            mapOf("x" to i("x w pikselach (0..100 = procent)"), "y" to i("y w pikselach (0..100 = procent)")),
            listOf("x", "y"))
        add("screen_swipe", "Przesuń (scroll/pull). Piksele (0..100 w obu osiach = procenty).",
            mapOf("x1" to i(""), "y1" to i(""), "x2" to i(""), "y2" to i(""), "duration_ms" to i("domyślnie 350")),
            listOf("x1", "y1", "x2", "y2"))
        add("screen_text", "Wpisz tekst do aktywnego pola.", mapOf("text" to s("tekst")), listOf("text"))
        add("screen_back", "Naciśnij wstecz.", emptyMap(), emptyList())
        add("screen_home", "Naciśnij home.", emptyMap(), emptyList())
        add("screen_recent", "Pokaż ostatnie aplikacje.", emptyMap(), emptyList())
        add("screen_click", "Kliknij element ekranu po widocznym tekście. Nie zgaduj pikseli.",
            mapOf("text" to s("tekst na ekranie")), listOf("text"))
        add("screen_read", "Odczytaj widoczny tekst z ekranu (bez zgody na nagranie).", emptyMap(), emptyList())
        add(
            "life",
            "Listy życia: zadania, zakupy, ludzie, notatki, plan lekcji, kopia. Akcje list/add/done/backup.",
            mapOf(
                "action" to s("list|add|done|backup"),
                "kind" to s("todo|shopping|people|homework|notes|lessons"),
                "title" to s("tresc / imie / produkt"),
                "extra" to s("kategoria, jutro, nastepna lekcja"),
            ),
            listOf("action", "kind"),
        )
        add(
            "session",
            "Sterowanie rozmowa: end = zakoncz, later = jeszcze poczekaj.",
            mapOf("action" to s("end|later")),
            listOf("action"),
        )

        return arr
    }

    // ── Dispatch ───────────────────────────────────────────────────────────

    suspend fun dispatch(name: String, args: JSONObject): ToolResult = try {
        when (name) {
            "system_now" -> SystemTools.now()
            "media_pause" -> SystemTools.mediaPause()
            "media_resume" -> SystemTools.mediaResume()
            "open_app" -> SystemTools.openApp(args.optString("app", ""))
            "phone" -> PhoneTools.run(
                args.optString("action", "status"),
                args.optString("value", ""),
                args.optString("extra", ""),
            )
            "user_profile" -> SystemTools.userProfile()
            "user_profile_update" -> SystemTools.updateProfile(args.optJSONObject("fields") ?: JSONObject())

            "memory_store" -> MemoryTools.store(args.optString("fact", ""), args.optString("category", ""))
            "memory_recall" -> MemoryTools.recall(args.optString("query", ""))
            "memory_forget" -> MemoryTools.forget(args.optString("fact", ""))

            "db_list_tables" -> SupabaseTools.listTables()
            "db_describe" -> SupabaseTools.describe(args.optString("table", ""))
            "db_query" -> SupabaseTools.query(
                args.optString("table", ""), args.optJSONObject("filters") ?: JSONObject(),
                args.optInt("limit", 20)
            )
            "db_insert" -> SupabaseTools.insert(args.optString("table", ""), args.optJSONObject("row") ?: JSONObject())
            "db_update" -> SupabaseTools.update(args.optString("table", ""), args.optString("id", ""),
                args.optJSONObject("row") ?: JSONObject())
            "db_delete" -> SupabaseTools.delete(args.optString("table", ""), args.optString("id", ""))
            "db_ddl" -> SupabaseTools.ddlPropose(args.optString("sql", ""))

            "calendar_list" -> CalendarTools.list(args.optString("range", "dziś"))
            "calendar_add" -> CalendarTools.add(
                args.optString("title", ""), args.optString("start", ""),
                args.optString("end", ""), args.optString("location", ""), args.optString("notes", ""),
                args.optString("kind", "normal"),
            )
            "calendar_update" -> CalendarTools.update(
                args.optString("id", ""), args.optString("title", ""), args.optString("start", ""),
                args.optString("end", ""), args.optString("location", ""), args.optString("notes", ""),
                args.optString("kind", "")
            )
            "calendar_remove" -> CalendarTools.remove(args.optString("id", ""))
            "calendar_substitute" -> CalendarTools.substitute(args.optString("subject", ""))
            "calendar_mark" -> CalendarTools.mark(args.optString("kind", ""), args.optString("subject", ""))

            "alarm_list" -> AlarmTools.list()
            "alarm_add" -> AlarmTools.add(
                args.optString("time", ""), args.optString("label", ""),
                args.optString("days", "daily"), args.optString("sound", "")
            )
            "alarm_edit" -> AlarmTools.edit(
                args.optString("id", ""), args.optString("time", ""), args.optString("label", ""),
                args.optString("sound", ""), args.optString("active", "")
            )
            "alarm_remove" -> AlarmTools.remove(args.optString("id", ""))

            "reminder_list" -> ReminderTools.list()
            "reminder_add" -> ReminderTools.add(args.optString("title", ""), args.optString("when", ""))
            "reminder_done" -> ReminderTools.done(args.optString("id", ""))
            "reminder_remove" -> ReminderTools.remove(args.optString("id", ""))

            "notifications_read" -> NotificationTools.read(args.optString("app", ""), args.optInt("limit", 10))

            "spotify_status" -> if (SpotifyApi.isConnected())
                ToolResult.ok("Spotify jest połączony.") else
                ToolResult.fail("Spotify niepołączony. Podaj Client ID przez spotify_connect.")
            "spotify_connect" -> SpotifyTools.connect(args.optString("client_id", ""))
            "spotify_now_playing" -> SpotifyTools.nowPlaying()
            "spotify_pause" -> SpotifyTools.pause()
            "spotify_resume" -> SpotifyTools.resume()
            "spotify_next" -> SpotifyTools.next()
            "spotify_previous" -> SpotifyTools.previous()
            "spotify_seek" -> SpotifyTools.seek(args.optInt("position_sec", 0))
            "spotify_volume" -> SpotifyTools.volume(args.optInt("percent", 50))
            "spotify_play" -> SpotifyTools.play(args.optString("query", ""), args.optString("type", "track"))
            "spotify_list_playlists" -> SpotifyTools.listPlaylists(args.optInt("limit", 15))

            "screen_manual_start" -> ScreenTools.manualStart()
            "screen_manual_stop" -> ScreenTools.manualStop()
            "screen_get" -> ScreenTools.getScreen()
            "screen_tap" -> ScreenTools.tap(args.optInt("x", -1), args.optInt("y", -1))
            "screen_swipe" -> ScreenTools.swipe(
                args.optInt("x1", 0), args.optInt("y1", 0),
                args.optInt("x2", 0), args.optInt("y2", 0), args.optInt("duration_ms", 350)
            )
            "screen_text" -> ScreenTools.type(args.optString("text", ""))
            "screen_back" -> ScreenTools.back()
            "screen_home" -> ScreenTools.home()
            "screen_recent" -> ScreenTools.recents()
            "screen_click" -> ScreenTools.clickText(args.optString("text", ""))
            "screen_read" -> ScreenTools.readLabels()
            "life" -> LifeTools.run(
                args.optString("action", "list"),
                args.optString("kind", ""),
                args.optString("title", ""),
                args.optString("extra", ""),
            )
            "session" -> when (args.optString("action", "end").lowercase()) {
                "later", "poczekaj", "czekaj" -> {
                    NixiState.sessionKeepAliveAt = System.currentTimeMillis() + 150_000L
                    ToolResult.ok("Czekam jeszcze chwilę. Powiedz koniec, gdy skończysz.")
                }
                else -> {
                    LiveSessionService.stop(ToolContext.app, "narzedzie session")
                    ToolResult.ok("Koncze rozmowe.")
                }
            }

            else -> ToolResult.fail("Nieznane narzędzie: $name")
        }
    } catch (t: Throwable) {
        ToolResult.fail("Błąd narzędzia $name: ${t.message}")
    }
}
