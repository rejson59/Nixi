package dev.nixi.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import dev.nixi.NixiState
import dev.nixi.accessibility.NixiAccessibilityService
import dev.nixi.db.SupabaseHub
import dev.nixi.notif.NixiNotificationListener
import dev.nixi.store.LocalStore
import dev.nixi.tools.SpotifyApi
import dev.nixi.ui.components.GlassChip
import dev.nixi.ui.components.GlassDivider
import dev.nixi.ui.components.GlassTabs
import dev.nixi.ui.components.PillButton
import dev.nixi.ui.components.ScreenHeader
import dev.nixi.ui.components.SectionCard
import dev.nixi.ui.components.rememberOnResumeTick
import dev.nixi.ui.onboarding.NixiField
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.ui.theme.NixiWarn
import dev.nixi.util.DeviceTweaks
import dev.nixi.wake.EnrollmentController
import dev.nixi.wake.WakeWordService

/**
 * USTAWIENIA — posegregowane w cztery zakładki, żeby nie było jednej długiej
 * listy bez ładu:
 *
 *   Mózg     — Gemini, limity tokenów, głos i reakcje
 *   Nasłuch  — „Hej Nixi": czułość, tryb ECO, rejestracja, statystyki
 *   Dane     — Supabase, Spotify, prywatność
 *   Telefon  — praca w tle (HyperOS), uprawnienia systemowe
 *
 * Wszystkie ustawienia działają jak dotąd — zmieniło się tylko ich miejsce
 * i wygląd (szklane karty zamiast ciągu luźnych wierszy).
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current

    // ── stan pól (bez zmian względem poprzedniej wersji) ──────────────────
    var key by remember { mutableStateOf(LocalStore.geminiKey) }
    var model by remember { mutableStateOf(LocalStore.geminiModel) }
    var memoryModel by remember { mutableStateOf(LocalStore.memoryModel) }
    var tpmMode by remember { mutableStateOf(LocalStore.tpmMode) }
    var tpmLimit by remember { mutableStateOf(LocalStore.tpmCustomLimit.toString()) }

    var voice by remember { mutableStateOf(LocalStore.voiceName) }
    var playRate by remember { mutableStateOf(LocalStore.playRate) }
    var volume by remember { mutableStateOf(LocalStore.outputVolume) }
    var ding by remember { mutableStateOf(LocalStore.dingEnabled) }
    var trustDeletes by remember { mutableStateOf(LocalStore.trustDeletes) }
    var quietSession by remember { mutableStateOf(LocalStore.quietDuringSession) }

    var wakeOn by remember { mutableStateOf(LocalStore.wakeEnabled) }
    var sensitivity by remember { mutableStateOf(LocalStore.wakeSensitivity) }
    var eco by remember { mutableStateOf(LocalStore.ecoMode) }
    var enrollOpen by remember { mutableStateOf(false) }

    var sbUrl by remember { mutableStateOf(LocalStore.supabaseUrl) }
    var sbKey by remember { mutableStateOf(LocalStore.supabaseKey) }
    var sbPat by remember { mutableStateOf(LocalStore.supabasePat) }
    var sbStatus by remember { mutableStateOf("") }
    var sbBusy by remember { mutableStateOf(false) }
    val dbStatus by dev.nixi.db.DbProvisioner.status.collectAsState()
    val scope2 = androidx.compose.runtime.rememberCoroutineScope()

    var spId by remember { mutableStateOf(LocalStore.spotifyClientId) }
    var spStatus by remember {
        mutableStateOf(if (SpotifyApi.isConnected()) "Spotify połączony" else "Spotify niepołączony")
    }

    var wipeConfirm by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    // stan uprawnień/ustawień systemowych zmienia się poza aplikacją —
    // odświeżamy po każdym powrocie do NIXI
    val resumeTick = rememberOnResumeTick()
    val a11yOn = remember(resumeTick) { NixiAccessibilityService.isAvailable() }
    val listenerOn = remember(resumeTick) { NixiNotificationListener.isEnabled(context) }
    val batteryFree = remember(resumeTick) { DeviceTweaks.isIgnoringBatteryOptimizations(context) }
    val fsIntentOk = remember(resumeTick) { DeviceTweaks.isFullScreenIntentAllowed() }

    val enroll by EnrollmentController.state.collectAsState()
    val tpmInfo by NixiState.tpm.collectAsState()

    // Pierwsze wejście w „Dane": sprawdź, czego brakuje w bazie — bez
    // męczenia sieci przy każdym powrocie do ustawień.
    LaunchedEffect(tab, resumeTick) {
        if (tab == 2 && SupabaseHub.available && dev.nixi.db.DbProvisioner.status.value == null) {
            val st = dev.nixi.db.DbProvisioner.check()
            sbStatus = if (st.ready) st.summary
            else "${st.summary} — naciśnij „Utwórz / zaktualizuj tabele”."
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Ustawienia",
                subtitle = "cztery grupy — wszystko, czego potrzebuje NIXI",
            )
        }

        item {
            GlassTabs(
                labels = listOf("Mózg", "Nasłuch", "Dane", "Telefon"),
                selected = tab,
                onSelect = { tab = it },
            )
        }

        if (tab == 0) {
            // ── MÓZG: Gemini ───────────────────────────────────────────────
            item {
                SectionCard(
                    title = "Gemini — mózg NIXI",
                    subtitle = "Klucz API zostaje na telefonie; modele możesz podmienić w każdej chwili.",
                ) {
                    NixiField("Klucz API", key) { key = it }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            NixiField("Model Live", model) { model = it }
                        }
                        Column(Modifier.weight(1f)) {
                            NixiField("Model pamięci", memoryModel) { memoryModel = it }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    GlassDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Ochrona TPM — ile tokenów na minutę wolno zużyć",
                        color = NixiText, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    val modes = listOf(
                        Triple("eco 45K", "eco", "oszczędnie, ok. 45 tys. tokenów/min"),
                        Triple("std 55K", "standard", "standardowo, ok. 55 tys./min"),
                        Triple("własny", "custom", "Twój limit z pola niżej"),
                        Triple("wył.", "off", "bez limitu (uwaga na koszty)"),
                    )
                    GlassTabs(
                        labels = modes.map { it.first },
                        selected = modes.indexOfFirst { it.second == tpmMode }.coerceAtLeast(0),
                        onSelect = { tpmMode = modes[it].second },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        modes.firstOrNull { it.second == tpmMode }?.third ?: "",
                        color = NixiTextDim, fontSize = 11.sp,
                    )
                    if (tpmMode == "custom") {
                        Spacer(Modifier.height(10.dp))
                        NixiField("Limit tokenów / minutę", tpmLimit) { tpmLimit = it }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Teraz: ${tpmInfo.used} / ${tpmInfo.limit} tokenów w tej minucie" +
                            (if (tpmInfo.percent > 0) " (${tpmInfo.percent}%)" else "") +
                            (if (tpmInfo.backoffSec > 0) " • pauza ${tpmInfo.backoffSec}s" else ""),
                        color = NixiTextDim, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    PillButton(
                        text = "Zapisz ustawienia Gemini",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            LocalStore.geminiKey = key
                            LocalStore.geminiModel = model.ifBlank { "gemini-3.8-live" }
                            LocalStore.memoryModel = memoryModel.ifBlank { "gemini-3.8-flash" }
                            LocalStore.tpmMode = tpmMode
                            tpmLimit.toIntOrNull()?.let { LocalStore.tpmCustomLimit = it }
                        },
                    )
                }
            }

            // ── MÓZG: głos ─────────────────────────────────────────────────
            item {
                SectionCard(
                    title = "Głos i reakcje",
                    subtitle = "Jak NIXI ma mówić i jak ma Cię wołać.",
                    accent = NixiOk,
                ) {
                    NixiField("Głos (nazwa głosu Gemini)", voice) { voice = it }
                    Spacer(Modifier.height(12.dp))
                    SliderRow(
                        label = "Tempo odtwarzania: ${"%.2f".format(playRate)}x",
                        value = playRate, range = 0.7f..1.4f, onChange = { playRate = it },
                    )
                    SliderRow(
                        label = "Głośność głosu: ${(volume * 100).toInt()}%",
                        value = volume, range = 0.3f..1f, onChange = { volume = it },
                    )
                    SwitchRow("Dźwięk „mów” (ding po aktywacji)", ding) { ding = it }
                    SwitchRow(
                        "Usuwaj bez pytania (kalendarz, budzik, wiersze)",
                        trustDeletes,
                    ) {
                        trustDeletes = it
                        LocalStore.trustDeletes = it
                    }
                    SwitchRow(
                        "Ciche akcje w rozmowie (bez powiadomień o każdym narzędziu)",
                        quietSession,
                    ) {
                        quietSession = it
                        LocalStore.quietDuringSession = it
                    }
                    Spacer(Modifier.height(12.dp))
                    PillButton(
                        text = "Zapisz głos",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            LocalStore.voiceName = voice.ifBlank { "Puck" }
                            LocalStore.playRate = playRate
                            LocalStore.outputVolume = volume
                            LocalStore.dingEnabled = ding
                        },
                    )
                }
            }
        }

        if (tab == 1) {
            // ── NASŁUCH ────────────────────────────────────────────────────
            item {
                SectionCard(
                    title = "Nasłuch „Hej Nixi” (offline)",
                    subtitle = "Dźwięk zostaje na telefonie — nigdzie nie jest wysyłany.",
                ) {
                    SwitchRow("Aktywny", wakeOn) {
                        wakeOn = it
                        LocalStore.wakeEnabled = it
                        if (it) WakeWordService.start(context) else WakeWordService.stop(context)
                        // piesek: uzbrój albo odwołaj alarm (arm sam decyduje)
                        dev.nixi.boot.WakeWatchdog.arm(context)
                    }
                    Spacer(Modifier.height(10.dp))
                    SliderRow(
                        label = "Czułość: ${"%.0f".format(sensitivity * 100)}%",
                        value = sensitivity, range = 0f..1f,
                        onChange = {
                            sensitivity = it
                            LocalStore.wakeSensitivity = it
                            WakeWordService.engine.configure(
                                if (LocalStore.ecoMode) dev.nixi.wake.WakeEngine.Mode.ECO
                                else dev.nixi.wake.WakeEngine.Mode.STANDARD,
                                it
                            )
                        },
                    )
                    SwitchRow("Tryb ECO (mniej CPU, nieco wolniejsze wykrywanie)", eco) {
                        eco = it
                        LocalStore.ecoMode = it
                        WakeWordService.engine.configure(
                            if (it) dev.nixi.wake.WakeEngine.Mode.ECO
                            else dev.nixi.wake.WakeEngine.Mode.STANDARD,
                            LocalStore.wakeSensitivity
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Koszt: ${LocalStore.wakeCpuMsPerMin} ms CPU na minutę nasłuchu " +
                            "(w ciszy prawie zero — analiza widma rusza dopiero na mowę).",
                        color = NixiTextDim, fontSize = 11.sp,
                    )
                }
            }

            item {
                SectionCard(
                    title = "Twój wzorzec frazy",
                    subtitle = "Trzy nagrania „Hej Nixi” — z nich NIXI uczy się Twojego głosu.",
                    accent = if (LocalStore.wakeNeedsEnroll) NixiWarn else NixiOk,
                ) {
                    if (LocalStore.wakeNeedsEnroll) {
                        Text(
                            "Wzorzec pochodzi ze starszego detektora — nagraj „Hej Nixi” " +
                                "ponownie (3 próby), żeby nasłuch działał.",
                            color = NixiWarn, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (LocalStore.wakeNeedsEnroll) "Wymaga rejestracji"
                            else "Wzorzec gotowy",
                            color = if (LocalStore.wakeNeedsEnroll) NixiWarn else NixiOk,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        PillButton(
                            text = if (enrollOpen) "Zamknij" else "Nagraj od nowa",
                            filled = false,
                            onClick = { enrollOpen = !enrollOpen },
                        )
                    }
                    if (enrollOpen) {
                        Spacer(Modifier.height(12.dp))
                        GlassDivider()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (enroll.recording) "Mów: „Hej Nixi” (2 s)…" else enroll.message,
                            color = NixiText, fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PillButton(
                                text = if (enroll.recording) "Stop" else "Nagraj próbę (${enroll.takes}/3)",
                                onClick = { EnrollmentController.startAttempt() },
                            )
                            if (enroll.done) {
                                TextButton(onClick = { enrollOpen = false }) {
                                    Text("Gotowe", color = NixiOk)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Nagrywaj z tej samej odległości i w podobnych warunkach, " +
                                "w jakich zwykle mówisz „Hej Nixi”. NIXI odrzuca wtedy " +
                                "głosy innych osób i podobnie brzmiące słowa.",
                            color = NixiTextDim, fontSize = 11.sp,
                        )
                    }
                }
            }

            item {
                SectionCard(
                    title = "Jak to działa (w skrócie)",
                    subtitle = "Dwie tanie kontrole i Twój głos jako trzecia.",
                    accent = NixiPurple,
                ) {
                    StepLine("1", "Ciągły, tani detektor sprawdza, czy w strumieniu pojawiło się coś podobnego do frazy.")
                    StepLine("2", "Dopiero wtedy porównanie „na pełnej rozdzielczości” liczy, czy to naprawdę Twoje słowa.")
                    StepLine("3", "Na końcu podpis głosu: NIXI odrzuca wypowiedź kogoś innego albo znane fałszywe trafienie.")
                }
            }
        }

        if (tab == 2) {
            // ── DANE ───────────────────────────────────────────────────────
            item {
                SectionCard(
                    title = "Supabase — pamięć i tabele",
                    subtitle = "Twoje dane trzymają się u Ciebie w projekcie; NIXI tylko je czyta i zapisuje.",
                ) {
                    NixiField("URL projektu", sbUrl) { sbUrl = it }
                    Spacer(Modifier.height(10.dp))
                    NixiField("Klucz anon", sbKey) { sbKey = it }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassChip(
                            text = if (SupabaseHub.available) "połączono" else "brak konfiguracji",
                            dot = if (SupabaseHub.available) NixiOk else NixiWarn,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    PillButton(
                        text = "Zapisz i odkryj tabele",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            LocalStore.supabaseUrl = sbUrl
                            LocalStore.supabaseKey = sbKey
                            LocalStore.supabasePat = sbPat
                            SupabaseHub.rebuild()
                            SupabaseHub.refreshAll(force = true)
                            scope2.launch {
                                val st = dev.nixi.db.DbProvisioner.check()
                                if (st.ready) {
                                    sbStatus = "Zapisano. ${st.summary} — wszystko gotowe."
                                } else {
                                    // od razu próbujemy założyć brakujące tabele
                                    val out = dev.nixi.db.DbProvisioner.provision()
                                    sbStatus = "Zapisano. " + out.message
                                    if (out.needsManualSql) offerManualSql(context)
                                }
                            }
                        },
                    )
                    if (sbStatus.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(sbStatus, color = NixiTextDim, fontSize = 11.sp)
                    }
                }
            }

            item {
                val ready = dbStatus?.ready == true
                SectionCard(
                    title = "Struktura bazy",
                    subtitle = "NIXI zakłada brakujące tabele i dokłada nowe kolumny — sama.",
                    accent = when {
                        !SupabaseHub.available -> NixiWarn
                        ready -> NixiOk
                        else -> NixiWarn
                    },
                ) {
                    Text(
                        dbStatus?.summary ?: "Nie sprawdzałam jeszcze struktury bazy.",
                        color = NixiText, fontSize = 13.sp,
                    )
                    val missingNow = dbStatus?.missing.orEmpty()
                    if (missingNow.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Brakuje: " + missingNow.joinToString(", ") +
                                ". Naciśnij „Utwórz / zaktualizuj tabele”.",
                            color = NixiTextDim, fontSize = 11.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(
                            text = if (sbBusy) "Pracuję…" else "Utwórz / zaktualizuj tabele",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                sbBusy = true
                                scope2.launch {
                                    LocalStore.supabaseUrl = sbUrl
                                    LocalStore.supabaseKey = sbKey
                                    LocalStore.supabasePat = sbPat
                                    SupabaseHub.rebuild()
                                    SupabaseHub.refreshAll(force = true)
                                    val out = dev.nixi.db.DbProvisioner.provision()
                                    sbStatus = out.message
                                    if (out.needsManualSql) offerManualSql(context)
                                    sbBusy = false
                                }
                            },
                        )
                        PillButton(
                            text = "Sprawdź",
                            filled = false,
                            onClick = {
                                scope2.launch {
                                    LocalStore.supabaseUrl = sbUrl
                                    LocalStore.supabaseKey = sbKey
                                    SupabaseHub.rebuild()
                                    SupabaseHub.refreshAll(force = true)
                                    val st = dev.nixi.db.DbProvisioner.check()
                                    sbStatus = "Sprawdzone: ${st.summary}."
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    GlassDivider()
                    Spacer(Modifier.height(12.dp))
                    NixiField(
                        "Token osobisty Supabase (opcjonalny, sbp_…)",
                        sbPat,
                    ) { sbPat = it }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Klucz anon nie ma prawa zmieniać struktury bazy (to zabezpieczenie " +
                            "Supabase). Z tokenem osobistym NIXI zrobi to sama, bez wklejania SQL-a. " +
                            "Token trzyma się tylko na tym telefonie.",
                        color = NixiTextDim, fontSize = 11.sp,
                    )
                    if (sbStatus.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(sbStatus, color = NixiTextDim, fontSize = 11.sp)
                    }
                }
            }

            item {
                SectionCard(
                    title = "Spotify",
                    subtitle = "Potrzebne tylko do sterowania muzyką głosem.",
                    accent = NixiOk,
                ) {
                    NixiField("Client ID (developer.spotify.com)", spId) { spId = it }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillButton(
                            text = "Połącz",
                            onClick = {
                                LocalStore.spotifyClientId = spId
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            android.net.Uri.parse(SpotifyApi.buildAuthUrl(spId))
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                    spStatus = "Otworzyłam logowanie Spotify w przeglądarce — " +
                                        "po zatwierdzeniu powrócisz automatycznie."
                                }.onFailure { spStatus = "Błąd: ${it.message}" }
                            },
                        )
                        PillButton(
                            text = "Odłącz",
                            filled = false,
                            onClick = {
                                SpotifyApi.disconnect()
                                spStatus = "Odłączono."
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    GlassChip(
                        text = spStatus,
                        dot = if (SpotifyApi.isConnected()) NixiOk else NixiTextDim,
                    )
                }
            }

            item {
                SectionCard(
                    title = "Prywatność",
                    subtitle = "Klucze API i wzorzec frazy leżą tylko na tym telefonie.",
                    accent = NixiWarn,
                ) {
                    Text(
                        "Czyszczenie usuwa klucze, wzorzec „Hej Nixi” i ustawienia lokalne. " +
                            "Dane w Supabase zostają nietknięte (zarządzasz nimi na ekranie Tabele).",
                        color = NixiTextDim, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    PillButton(
                        text = "Wyczyść dane lokalne",
                        filled = false,
                        onClick = { wipeConfirm = true },
                    )
                }
            }
        }

        if (tab == 3) {
            // ── TELEFON ────────────────────────────────────────────────────
            item {
                SectionCard(
                    title = "Praca w tle" + if (DeviceTweaks.isXiaomi) " (Xiaomi/HyperOS)" else "",
                    subtitle = "Bez tego system potrafi ubić nasłuch po kilku minutach.",
                ) {
                    Text(
                        "Zezwól NIXI na pracę bez ograniczeń. Na HyperOS dodatkowo " +
                            "przydaje się autostart — inaczej po restarcie telefonu " +
                            "nasłuch sam się nie podniesie.",
                        color = NixiTextDim, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    PermRow(
                        "Bateria bez ograniczeń",
                        batteryFree,
                        openSettings = {
                            if (!DeviceTweaks.openBatterySaver(context)) {
                                runCatching { context.startActivity(DeviceTweaks.appDetails(context)) }
                            }
                        },
                    )
                    if (DeviceTweaks.isXiaomi) {
                        PermRow(
                            "Autostart (po restarcie telefonu)",
                            !WakeWordService.running || batteryFree,
                            openSettings = { DeviceTweaks.openAutoStart(context) },
                        )
                        Spacer(Modifier.height(8.dp))
                        PillButton(
                            text = "Okna w tle i uprawnienia (HyperOS)",
                            filled = false,
                            onClick = { DeviceTweaks.openPermissionEditor(context) },
                        )
                    }
                    if (Build.VERSION.SDK_INT >= 34) {
                        PermRow(
                            "Wezwanie rozmowy na pełnym ekranie",
                            fsIntentOk,
                            openSettings = {
                                runCatching {
                                    context.startActivity(
                                        Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT")
                                            .setData(android.net.Uri.parse("package:" + context.packageName))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                        )
                    }
                }
            }

            item {
                SectionCard(
                    title = "Uprawnienia dodatkowe",
                    subtitle = "Każde z nich włącza jedną konkretną funkcję.",
                    accent = NixiOk,
                ) {
                    PermRow(
                        "Dostęp do powiadomień (odczyt + ciche reguły + pauza muzyki)",
                        listenerOn,
                        openSettings = {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                    )
                    if (a11yOn) {
                        PermRow(
                            "Usługa dostępności (sterowanie w trybie ręcznym)",
                            true,
                            openSettings = {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                        )
                    } else if (NixiAccessibilityService.isEnabledInSystem(context)) {
                        // włączona w systemie, ale jeszcze nie podłączona — to NIE błąd
                        PermRow(
                            "Usługa dostępności — włączona w systemie",
                            true,
                            grantedLabel = "włączona",
                            openSettings = {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                        )
                    } else {
                        PermRow(
                            "Usługa dostępności (tylko tryb ręczny)",
                            false,
                            grantedLabel = "wyłączona",
                            openSettings = {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                        )
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        PermRow(
                            "Dokładne alarmy (przypomnienia)",
                            exactAlarmsOk(context),
                            openSettings = {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        android.net.Uri.parse("package:" + context.packageName)
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    if (wipeConfirm) {
        AlertDialog(
            onDismissRequest = { wipeConfirm = false },
            containerColor = dev.nixi.ui.theme.NixiSurfaceHi,
            title = { Text("Wyczyścić dane lokalne?", color = NixiText) },
            text = {
                Text(
                    "Usunę: klucze API, szablon „Hej Nixi”, ustawienia. Dane w Supabase zostają.",
                    color = NixiTextDim
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LocalStore.clearAllData()
                    SupabaseHub.rebuild()
                    SupabaseHub.refreshAll(force = true)
                    wipeConfirm = false
                }) { Text("Usuń", color = NixiWarn) }
            },
            dismissButton = {
                TextButton(onClick = { wipeConfirm = false }) { Text("Anuluj", color = NixiTextDim) }
            },
        )
    }
}

private fun exactAlarmsOk(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 31) {
        val am = context.getSystemService(android.content.Context.ALARM_SERVICE)
            as android.app.AlarmManager
        am.canScheduleExactAlarms()
    } else true
}

/** Suwak z opisem — jeden wygląd we wszystkich miejscach. */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = NixiTextDim, fontSize = 12.sp)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/** Wiersz przełącznika (koniec z luźnymi „Surface” w liście). */
@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = NixiText, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** Wiersz uprawnienia: nazwa, stan i przycisk „Ustaw”, gdy brakuje zgody. */
@Composable
private fun PermRow(
    label: String,
    granted: Boolean,
    grantedLabel: String? = null,
    openSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = NixiText, fontSize = 13.sp)
            Text(
                grantedLabel ?: if (granted) "włączone" else "wymaga zgody",
                color = if (granted) NixiOk else NixiWarn, fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (!granted) {
            PillButton(text = "Ustaw", filled = false, onClick = openSettings)
        }
    }
}

/** Krótki, numerowany opis działania — zamiast ściany tekstu. */
@Composable
private fun StepLine(number: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            number,
            color = NixiPurple,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(18.dp),
        )
        Text(text, color = NixiTextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * Gdy klucz anon nie może zmienić struktury bazy (a nie ma tokenu osobistego):
 * kopiujemy gotowy SQL do schowka i otwieramy SQL Editor tego projektu —
 * użytkownikowi zostaje jedno wklejenie, a potem aplikacja robi resztę sama.
 */
private fun offerManualSql(context: Context) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(
            android.content.ClipData.newPlainText("nixi-schema", dev.nixi.db.DbSchema.SQL)
        )
    }
    val ref = dev.nixi.db.DbProvisioner.projectRef()
    if (ref.isNotBlank()) {
        runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://supabase.com/dashboard/project/$ref/sql/new")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
