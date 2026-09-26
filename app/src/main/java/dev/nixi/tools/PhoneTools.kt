package dev.nixi.tools

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.Settings
import dev.nixi.NixiState
import dev.nixi.accessibility.NixiAccessibilityService
import dev.nixi.util.LogBus

/**
 * Sterowanie telefonem — jedno narzędzie `phone` z wieloma akcjami,
 * żeby nie rozsadzać limitu deklaracji Gemini Live.
 */
object PhoneTools {

    @Volatile private var torchOn = false

    fun run(action: String, value: String, extra: String): ToolResult = try {
        when (norm(action)) {
            "status", "stan", "" -> status()
            "volume", "glosnosc", "głośność" -> volume(value, extra)
            "brightness", "jasnosc", "jasność" -> brightness(value)
            "torch", "latarka", "flashlight" -> torch(value)
            "timer", "minutnik" -> timer(value, extra)
            "web", "szukaj", "search", "url" -> web(value)
            "maps", "nawigacja", "navigate" -> maps(value)
            "clipboard", "schowek" -> clipboard(value, extra)
            "dial", "dzwon" -> dial(value, callNow = false)
            "call", "polacz", "połącz" -> dial(value, callNow = true)
            "sms", "wiadomosc", "wiadomość" -> sms(value, extra)
            "share", "udostepnij", "udostępnij" -> share(value.ifBlank { NixiState.lastSaid.value })
            "ringer", "dzwonek", "tryb" -> ringer(value)
            "screenshot", "zrzut" -> screenshot()
            "lock", "zablokuj" -> lock()
            "apps", "aplikacje" -> listApps(value)
            "settings", "ustawienia" -> openSettings(value)
            "vibrate", "wibruj" -> vibrate()
            "find", "znajdz", "znajdź" -> findPhone()
            "notifications_clear", "wycisc", "wyczyść" -> clearNotifications()
            "contacts", "kontakty" -> contacts(value)
            "inbox", "smsy", "wiadomosci" -> inbox()
            "location", "lokalizacja", "gdzie" -> location()
            "wifi", "wi-fi" -> wifi()
            "dnd", "nieprzeszkadzac" -> openSettings("dnd")
            "cicho" -> ringer("cichy")
            "bluetooth", "bt" -> openSettings("bt")
            "hotspot" -> openSettings("hotspot")
            "report", "raport" -> report()
            "backup", "kopia" -> LifeTools.dumpBackup()
            "diary", "dziennik", "vulcan", "eduvulcan" -> SystemTools.openApp("eduvulcan")
            else -> ToolResult.fail(
                "Nie znam akcji „$action”. Dostępne: status, volume, brightness, torch, " +
                    "timer, web, maps, clipboard, dial, sms, share, ringer, screenshot, " +
                    "lock, apps, settings, vibrate, find, contacts, inbox, location, wifi, report, call, dnd, backup, diary, bluetooth."
            )
        }
    } catch (t: Throwable) {
        LogBus.log("phone", "${t.javaClass.simpleName}: ${t.message}", "warn")
        ToolResult.fail("Nie udało się ($action): ${t.message ?: "błąd systemu"}")
    }

    fun status(): ToolResult {
        val ctx = ToolContext.app
        val am = audio()
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val ringer = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "cichy"
            AudioManager.RINGER_MODE_VIBRATE -> "wibracje"
            else -> "normalny"
        }
        val bright = try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Throwable) { -1 }
        val pct = if (bright >= 0) ((bright / 255f) * 100).toInt() else -1
        return ToolResult.ok(
            SystemTools.now().text +
                " Głośność multimediów: $vol/$max. Dzwonek: $ringer." +
                (if (pct >= 0) " Jasność: $pct%." else "") +
                (if (torchOn) " Latarka włączona." else "") +
                " Wyjście: ${outputRoute()}."
        )
    }

    private fun volume(value: String, streamHint: String): ToolResult {
        val am = audio()
        val stream = when (norm(streamHint)) {
            "ring", "dzwonek" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notify", "powiadomienia" -> AudioManager.STREAM_NOTIFICATION
            "call", "rozmowa" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(stream)
        val cur = am.getStreamVolume(stream)
        val v = norm(value)
        val target = when {
            v in listOf("mute", "wycisz", "0") -> 0
            v in listOf("max", "pełna", "pelna") -> max
            v in listOf("up", "głośniej", "glosniej", "+") -> (cur + 1).coerceAtMost(max)
            v in listOf("down", "ciszej", "-") -> (cur - 1).coerceAtLeast(0)
            else -> {
                val p = parsePercent(value) ?: return ToolResult.fail(
                    "Podaj głośność: 0–100, up/down, mute/max."
                )
                ((p / 100.0) * max).toInt().coerceIn(0, max)
            }
        }
        am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        val pct = if (max > 0) (target * 100 / max) else 0
        return ToolResult.ok("Głośność ${streamName(stream)}: $pct% ($target/$max).")
    }

    private fun brightness(value: String): ToolResult {
        val ctx = ToolContext.app
        if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(ctx)) {
            return ToolResult.fail(
                "Brak zgody na jasność ekranu. Ustawienia → Aplikacje → NIXI → " +
                    "Modyfikowanie ustawień systemowych — włącz raz."
            )
        }
        val p = parsePercent(value) ?: return ToolResult.fail("Podaj jasność 0–100.")
        val level = (p / 100.0 * 255).toInt().coerceIn(1, 255)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
        return ToolResult.ok("Jasność ustawiona na $p%.")
    }

    private fun torch(value: String): ToolResult {
        val ctx = ToolContext.app
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cam ->
            val c = cm.getCameraCharacteristics(cam)
            val flash = c.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)
            flash == true
        } ?: return ToolResult.fail("Ten telefon nie ma latarki.")
        val on = when (norm(value)) {
            "off", "wyłącz", "wylacz", "0", "false" -> false
            "on", "włącz", "wlacz", "1", "true", "" -> true
            "toggle", "przełącz", "przelacz" -> !torchOn
            else -> true
        }
        cm.setTorchMode(id, on)
        torchOn = on
        return ToolResult.ok(if (on) "Latarka włączona." else "Latarka wyłączona.")
    }

    private fun timer(value: String, label: String): ToolResult {
        val sec = parseSeconds(value) ?: return ToolResult.fail(
            "Podaj czas minutnika, np. 5 min, 90, 1:30."
        )
        if (sec !in 1..24 * 3600) return ToolResult.fail("Czas minutnika poza zakresem.")
        val ctx = ToolContext.app
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, sec)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (Build.VERSION.SDK_INT >= 31) putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        val m = sec / 60
        val s = sec % 60
        return ToolResult.ok("Minutnik na ${if (m > 0) "$m min " else ""}${if (s > 0) "$s s" else ""}.")
    }

    private fun web(value: String): ToolResult {
        val q = value.trim()
        if (q.isBlank()) return ToolResult.fail("Podaj adres albo czego szukać.")
        val uri = if (q.startsWith("http://") || q.startsWith("https://")) Uri.parse(q)
        else Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))
        launch(Intent(Intent.ACTION_VIEW, uri))
        return ToolResult.ok(if (q.startsWith("http")) "Otworzyłam link." else "Szukam: $q")
    }

    private fun maps(value: String): ToolResult {
        val q = value.trim()
        if (q.isBlank()) return ToolResult.fail("Podaj miejsce albo adres.")
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(q))
        launch(Intent(Intent.ACTION_VIEW, uri))
        return ToolResult.ok("Otwieram mapy: $q")
    }

    private fun clipboard(value: String, extra: String): ToolResult {
        val cm = ToolContext.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val v = norm(value)
        return if (v in listOf("get", "odczyt", "pokaż", "pokaz", "")) {
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ToolContext.app)?.toString().orEmpty()
            if (t.isBlank()) ToolResult.ok("Schowek jest pusty.")
            else ToolResult.ok("W schowku: ${t.take(400)}")
        } else {
            val text = extra.ifBlank { value }
            cm.setPrimaryClip(ClipData.newPlainText("nixi", text))
            ToolResult.ok("Skopiowałam do schowka.")
        }
    }

    private fun dial(value: String, callNow: Boolean): ToolResult {
        val hit = resolveContact(value) ?: return ToolResult.fail(
            "Nie wiem, do kogo dzwonić („$value”). Podaj numer albo imię z kontaktów."
        )
        val n = hit.first
        val tel = hit.second
        val uri = Uri.parse("tel:$tel")
        if (callNow && PermAsk.call()) {
            launch(Intent(Intent.ACTION_CALL, uri))
            clickSoon(listOf("Zadzwoń", "Call", "Połącz"))
            return ToolResult.ok("Łączę z $n.")
        }
        launch(Intent(Intent.ACTION_DIAL, uri))
        clickSoon(listOf("Zadzwoń", "Call", "Połącz"))
        return ToolResult.ok(
            if (callNow) "Potrzebuję zgody na połączenia — otworzyłam dialer. Potwierdź „Zadzwoń” albo daj uprawnienie i powtórz."
            else "Otworzyłam dialer: $n. Klikam „Zadzwoń”, jeśli widać przycisk."
        )
    }

    private fun clickSoon(labels: List<String>) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val acc = NixiAccessibilityService.instance ?: return@postDelayed
            for (l in labels) {
                if (acc.clickText(l) != null) return@postDelayed
            }
        }, 900)
    }

    private fun sms(to: String, body: String): ToolResult {
        val hit = resolveContact(to) ?: return ToolResult.fail(
            "Nie wiem, do kogo pisać („$to”). Podaj numer albo imię z kontaktów."
        )
        val tel = hit.second
        if (body.isNotBlank() && PermAsk.sendSms()) {
            return try {
                val sm = android.telephony.SmsManager.getDefault()
                sm.sendTextMessage(tel, null, body.take(480), null, null)
                ToolResult.ok("Wysłałam SMS do ${hit.first}: ${body.take(80)}")
            } catch (t: Throwable) {
                ToolResult.fail("Nie wysłałam SMS: ${t.message}")
            }
        }
        val uri = Uri.parse("smsto:$tel")
        val i = Intent(Intent.ACTION_SENDTO, uri)
        if (body.isNotBlank()) i.putExtra("sms_body", body)
        launch(i)
        clickSoon(listOf("Wyślij", "Send", "SMS"))
        return ToolResult.ok("Otworzyłam SMS do ${hit.first} — klikam Wyślij, jeśli widać przycisk.")
    }

    /** Numer albo imię z książki. */
    private fun resolveContact(raw: String): Pair<String, String>? {
        val q = raw.trim()
        if (q.isBlank()) return null
        val digits = q.filter { it.isDigit() || it == '+' }
        if (digits.length >= 6) return q to digits
        if (!PermAsk.contacts()) return null
        val cr = ToolContext.app.contentResolver
        val cur = cr.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        ) ?: return null
        var best: Pair<String, String>? = null
        val needle = q.lowercase()
        cur.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val num = (it.getString(1) ?: "").filter { ch -> ch.isDigit() || ch == '+' }
                if (num.length < 6) continue
                val ln = name.lowercase()
                if (ln == needle || ln.startsWith(needle) || ln.contains(needle)) {
                    best = name to num
                    if (ln == needle || ln.startsWith(needle)) break
                }
            }
        }
        return best
    }

    private fun share(value: String): ToolResult {
        if (value.isBlank()) return ToolResult.fail("Podaj tekst do udostępnienia.")
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ToolContext.app.startActivity(Intent.createChooser(i, "NIXI").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.ok("Otworzyłam udostępnianie.")
    }

    private fun ringer(value: String): ToolResult {
        val am = audio()
        val mode = when (norm(value)) {
            "silent", "cichy", "mute" -> AudioManager.RINGER_MODE_SILENT
            "vibrate", "wibracje", "wibracja" -> AudioManager.RINGER_MODE_VIBRATE
            "normal", "normalny", "głośny", "glosny" -> AudioManager.RINGER_MODE_NORMAL
            else -> return ToolResult.fail("Podaj tryb: normalny / wibracje / cichy.")
        }
        am.ringerMode = mode
        return ToolResult.ok(
            "Dzwonek: " + when (mode) {
                AudioManager.RINGER_MODE_SILENT -> "cichy"
                AudioManager.RINGER_MODE_VIBRATE -> "wibracje"
                else -> "normalny"
            } + "."
        )
    }

    private fun screenshot(): ToolResult {
        val svc = NixiAccessibilityService.instance
            ?: return ToolResult.fail("Potrzebna usługa dostępności (tryb ręczny) do zrzutu.")
        val ok = if (Build.VERSION.SDK_INT >= 28) {
            svc.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else false
        return if (ok) ToolResult.ok("Zrzut ekranu zrobiony.")
        else ToolResult.fail("Nie udało się zrobić zrzutu.")
    }

    private fun lock(): ToolResult {
        val svc = NixiAccessibilityService.instance
            ?: return ToolResult.fail("Potrzebna usługa dostępności, żeby zablokować ekran.")
        val ok = if (Build.VERSION.SDK_INT >= 28) {
            svc.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        } else false
        return if (ok) ToolResult.ok("Ekran zablokowany.")
        else ToolResult.fail("Nie udało się zablokować ekranu.")
    }

    private fun listApps(query: String): ToolResult {
        val ctx = ToolContext.app
        val pm = ctx.packageManager
        val q = query.trim().lowercase()
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = pm.queryIntentActivities(launch, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { q.isBlank() || it.first.lowercase().contains(q) || it.second.contains(q) }
            .sortedBy { it.first.lowercase() }
            .take(20)
        if (found.isEmpty()) return ToolResult.ok("Nie znalazłam aplikacji „$query”.")
        return ToolResult.ok("Aplikacje:\n" + found.joinToString("\n") { "- ${it.first}" })
    }

    private fun openSettings(page: String): ToolResult {
        val action = when (norm(page)) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bt", "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "sound", "dźwięk", "dzwiek" -> Settings.ACTION_SOUND_SETTINGS
            "battery", "bateria" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "dnd", "nieprzeszkadzac" -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
            "apps", "aplikacje" -> Settings.ACTION_APPLICATION_SETTINGS
            "display", "ekran" -> Settings.ACTION_DISPLAY_SETTINGS
            "date", "czas" -> Settings.ACTION_DATE_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        launch(Intent(action))
        return ToolResult.ok("Otworzyłam ustawienia${if (page.isNotBlank()) " ($page)" else ""}.")
    }

    /** Głośno + wibracja — „gdzie jest telefon”. */
    private fun findPhone(): ToolResult {
        val am = audio()
        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND,
        )
        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        repeat(3) {
            vibrate()
            try { Thread.sleep(350) } catch (_: InterruptedException) { }
        }
        return ToolResult.ok("Daję znać — maksymalna głośność i wibracja.")
    }

    private fun vibrate(): ToolResult {
        val ctx = ToolContext.app
        val vib = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= 26) {
            vib.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(250)
        }
        return ToolResult.ok("Wibracja.")
    }

    private fun contacts(query: String): ToolResult {
        if (!PermAsk.contacts()) {
            return ToolResult.fail("Potrzebuję kontaktów — zatwierdź dialog i powtórz.")
        }
        val q = query.trim().lowercase()
        val cr = ToolContext.app.contentResolver
        val cur = cr.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        ) ?: return ToolResult.ok("Brak kontaktów.")
        val out = ArrayList<String>()
        cur.use {
            while (it.moveToNext() && out.size < 12) {
                val name = it.getString(0) ?: continue
                val num = it.getString(1) ?: ""
                if (q.isNotBlank() && !name.lowercase().contains(q) && !num.contains(q)) continue
                out.add("$name: $num")
            }
        }
        return if (out.isEmpty()) ToolResult.ok("Nie znalazłam kontaktu.")
        else ToolResult.ok(out.joinToString("\n"))
    }

    private fun inbox(): ToolResult {
        if (!PermAsk.receiveSms()) return ToolResult.fail("Potrzebuję SMS — zatwierdź dialog i powtórz.")
        val cur = ToolContext.app.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"),
            null, null, "date DESC",
        ) ?: return ToolResult.ok("Skrzynka pusta albo niedostępna.")
        val out = ArrayList<String>()
        cur.use {
            while (it.moveToNext() && out.size < 8) {
                val who = it.getString(0) ?: "?"
                val body = (it.getString(1) ?: "").take(120)
                out.add("$who: $body")
            }
        }
        return if (out.isEmpty()) ToolResult.ok("Brak SMS.")
        else ToolResult.ok(out.joinToString("\n"))
    }

    private fun location(): ToolResult {
        if (!PermAsk.location()) return ToolResult.fail("Potrzebuję lokalizacji — zatwierdź dialog i powtórz.")
        val lm = ToolContext.app.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val loc = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        ).mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time }
            ?: return ToolResult.ok("Nie mam jeszcze ostatniej lokalizacji.")
        return ToolResult.ok("Ostatnia pozycja: ${loc.latitude}, ${loc.longitude}.")
    }

    private fun wifi(): ToolResult {
        val wm = ToolContext.app.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val on = wm.isWifiEnabled
        launch(Intent(Settings.ACTION_WIFI_SETTINGS))
        return ToolResult.ok(if (on) "Wi‑Fi włączone — otworzyłam ustawienia." else "Wi‑Fi wyłączone — otworzyłam ustawienia.")
    }

    private fun report(): ToolResult {
        val snap = dev.nixi.util.ErrorReport.snapshot()
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, snap)
            putExtra(Intent.EXTRA_SUBJECT, "NIXI raport")
        }
        launch(Intent.createChooser(i, "Raport NIXI"))
        return ToolResult.ok("Otworzyłam udostępnianie raportu.")
    }

    private fun clearNotifications(): ToolResult {
        val l = dev.nixi.notif.NixiNotificationListener.instance
            ?: return ToolResult.fail("Brak dostępu do powiadomień.")
        return try {
            l.cancelAllNotifications()
            ToolResult.ok("Wyczyściłam powiadomienia.")
        } catch (t: Throwable) {
            ToolResult.fail("Nie mogę czyścić powiadomień: ${t.message}")
        }
    }

    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ToolContext.app.startActivity(intent)
    }

    private fun audio(): AudioManager =
        ToolContext.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun outputRoute(): String {
        val am = audio()
        val types = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        }.getOrDefault(emptyList())
        return when {
            types.any { t ->
                t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= 31 && t == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
            } -> "słuchawki Bluetooth"
            types.any { it == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES } -> "słuchawki"
            else -> "głośnik"
        }
    }

    private fun streamName(s: Int) = when (s) {
        AudioManager.STREAM_RING -> "dzwonka"
        AudioManager.STREAM_ALARM -> "alarmu"
        AudioManager.STREAM_NOTIFICATION -> "powiadomień"
        AudioManager.STREAM_VOICE_CALL -> "rozmowy"
        else -> "multimediów"
    }

    private fun norm(s: String) = s.trim().lowercase()
        .replace("ą", "a").replace("ć", "c").replace("ę", "e")
        .replace("ł", "l").replace("ń", "n").replace("ó", "o")
        .replace("ś", "s").replace("ź", "z").replace("ż", "z")

    /** 0–100 albo „50%”. */
    fun parsePercent(raw: String): Int? {
        val d = raw.trim().removeSuffix("%").trim()
        val n = d.toIntOrNull() ?: return null
        return n.coerceIn(0, 100)
    }

    /** „5 min”, „90”, „1:30”, „2 godziny”. */
    fun parseSeconds(raw: String): Int? {
        val t = raw.trim().lowercase()
        Regex("""^(\d+):(\d{1,2})$""").find(t)?.let {
            val m = it.groupValues[1].toInt()
            val s = it.groupValues[2].toInt()
            return m * 60 + s
        }
        val num = Regex("""(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return when {
            t.contains("godz") || t.contains("h") -> num * 3600
            t.contains("min") -> num * 60
            t.contains("sek") || t.contains("s") && !t.contains("min") -> num
            num <= 180 -> num * 60 // gołe „5” = 5 minut (minutnik)
            else -> num
        }
    }
}
