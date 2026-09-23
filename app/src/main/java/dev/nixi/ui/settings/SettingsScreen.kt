package dev.nixi.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nixi.accessibility.NixiAccessibilityService
import dev.nixi.NixiState
import dev.nixi.db.SupabaseHub
import dev.nixi.notif.NixiNotificationListener
import dev.nixi.store.LocalStore
import dev.nixi.tools.SpotifyApi
import dev.nixi.ui.components.rememberOnResumeTick
import dev.nixi.ui.onboarding.NixiField
import dev.nixi.util.DeviceTweaks
import dev.nixi.ui.theme.NixiBg
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiSurface
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.ui.theme.NixiWarn
import dev.nixi.wake.EnrollmentController
import dev.nixi.wake.WakeWordService

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var key by remember { mutableStateOf(LocalStore.geminiKey) }
    var model by remember { mutableStateOf(LocalStore.geminiModel) }
    var memoryModel by remember { mutableStateOf(LocalStore.memoryModel) }
    var tpmMode by remember { mutableStateOf(LocalStore.tpmMode) }
    var tpmLimit by remember { mutableStateOf(LocalStore.tpmCustomLimit.toString()) }

    var voice by remember { mutableStateOf(LocalStore.voiceName) }
    var playRate by remember { mutableStateOf(LocalStore.playRate) }
    var volume by remember { mutableStateOf(LocalStore.outputVolume) }
    var ding by remember { mutableStateOf(LocalStore.dingEnabled) }

    var wakeOn by remember { mutableStateOf(LocalStore.wakeEnabled) }
    var sensitivity by remember { mutableStateOf(LocalStore.wakeSensitivity) }
    var eco by remember { mutableStateOf(LocalStore.ecoMode) }
    var enrollOpen by remember { mutableStateOf(false) }

    var sbUrl by remember { mutableStateOf(LocalStore.supabaseUrl) }
    var sbKey by remember { mutableStateOf(LocalStore.supabaseKey) }
    var sbStatus by remember { mutableStateOf("") }

    var spId by remember { mutableStateOf(LocalStore.spotifyClientId) }
    var spStatus by remember {
        mutableStateOf(if (SpotifyApi.isConnected()) "Spotify połączony" else "Spotify niepołączony")
    }

    var wipeConfirm by remember { mutableStateOf(false) }

    // stan uprawnień/ustawień systemowych zmienia się poza aplikacją —
    // odświeżamy po każdym powrocie do NIXI
    val resumeTick = rememberOnResumeTick()
    val a11yOn = remember(resumeTick) { NixiAccessibilityService.isAvailable() }
    val listenerOn = remember(resumeTick) { NixiNotificationListener.isEnabled(context) }
    val batteryFree = remember(resumeTick) { DeviceTweaks.isIgnoringBatteryOptimizations(context) }
    val fsIntentOk = remember(resumeTick) { DeviceTweaks.isFullScreenIntentAllowed() }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val enroll by EnrollmentController.state.collectAsState()
    val tpmInfo by NixiState.tpm.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionTitle("Gemini (mózg)") }
        item { NixiField("Klucz API", key) { key = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { NixiField("Model Live (np. gemini-3.8-live)", model) { model = it } }
                Column(Modifier.weight(1f)) { NixiField("Model pamięci (flash)", memoryModel) { memoryModel = it } }
            }
        }
        item {
            Column {
                Text("Ochrona TPM (limit tokenów/min):", color = NixiText, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("eco 45K", "std 55K", "custom", "wył.").forEach { m ->
                    val (label, value) = when (m) {
                        "eco 45K" -> m to "eco"
                        "std 55K" -> m to "standard"
                        "custom" -> m to "custom"
                        else -> m to "off"
                    }
                    Surface(
                        color = if (tpmMode == value) NixiPurple else NixiSurface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { tpmMode = value },
                    ) {
                        Text(label, color = if (tpmMode == value) Color.White else NixiText,
                            fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                    }
                }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Teraz: ${tpmInfo.used} / ${tpmInfo.limit} tokenów w tej minucie" +
                        (if (tpmInfo.percent > 0) " (${tpmInfo.percent}%)" else ""),
                    color = NixiTextDim, fontSize = 11.sp,
                )
            }
        }
        if (tpmMode == "custom") {
            item { NixiField("Limit tokenów / minutę", tpmLimit) { tpmLimit = it } }
        }
        item {
            Button(
                onClick = {
                    LocalStore.geminiKey = key
                    LocalStore.geminiModel = model.ifBlank { "gemini-3.8-live" }
                    LocalStore.memoryModel = memoryModel.ifBlank { "gemini-3.8-flash" }
                    LocalStore.tpmMode = tpmMode
                    tpmLimit.toIntOrNull()?.let { LocalStore.tpmCustomLimit = it }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NixiPurple),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Zapisz ustawienia Gemini") }
        }

        item { SectionTitle("Głos i reakcje") }
        item { NixiField("Głos (nazwa głosu Gemini)", voice) { voice = it } }
        item {
            Column {
                Text("Tempo odtwarzania: ${"%.2f".format(playRate)}x", color = NixiTextDim, fontSize = 12.sp)
                Slider(value = playRate, onValueChange = { playRate = it }, valueRange = 0.7f..1.4f)
            }
        }
        item {
            Column {
                Text("Głośność głosu: ${(volume * 100).toInt()}%", color = NixiTextDim, fontSize = 12.sp)
                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0.3f..1f)
            }
        }
        item {
            SwitchRow("Dźwięk „mów” (ding po aktywacji)", ding) { ding = it }
        }
        item {
            Button(
                onClick = {
                    LocalStore.voiceName = voice.ifBlank { "Puck" }
                    LocalStore.playRate = playRate
                    LocalStore.outputVolume = volume
                    LocalStore.dingEnabled = ding
                },
                colors = ButtonDefaults.buttonColors(containerColor = NixiPurple),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Zapisz głos") }
        }

        item { SectionTitle("Nasłuch „Hej Nixi” (offline)") }
        item {
            SwitchRow("Aktywny", wakeOn) {
                wakeOn = it
                LocalStore.wakeEnabled = it
                if (it) WakeWordService.start(context) else WakeWordService.stop(context)
            }
        }
        item {
            Column {
                Text("Czułość: ${"%.0f".format(sensitivity * 100)}%", color = NixiTextDim, fontSize = 12.sp)
                Slider(value = sensitivity, onValueChange = {
                    sensitivity = it
                    LocalStore.wakeSensitivity = it
                    WakeWordService.engine.configure(
                        if (LocalStore.ecoMode) dev.nixi.wake.WakeEngine.Mode.ECO
                        else dev.nixi.wake.WakeEngine.Mode.STANDARD,
                        it
                    )
                }, valueRange = 0f..1f)
            }
        }
        item {
            SwitchRow("Tryb ECO (mniej CPU, nieco wolniejsze wykrywanie)", eco) {
                eco = it
                LocalStore.ecoMode = it
                WakeWordService.engine.configure(
                    if (it) dev.nixi.wake.WakeEngine.Mode.ECO else dev.nixi.wake.WakeEngine.Mode.STANDARD,
                    LocalStore.wakeSensitivity
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Statystyki: ${LocalStore.wakeCpuMsPerMin} ms CPU / min nasłuchu. " +
                        "W tle wątek śpi — pobór niski.",
                    color = NixiTextDim, fontSize = 11.sp, modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { enrollOpen = true }) {
                    Text("Zarejestruj „Hej Nixi”", color = NixiPurple, fontSize = 12.sp)
                }
            }
        }
        if (enrollOpen) {
            item {
                Surface(color = NixiSurface, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            if (enroll.recording) "Mów: „Hej Nixi” (2 s)…"
                            else enroll.message,
                            color = NixiText, fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { EnrollmentController.startAttempt() },
                                colors = ButtonDefaults.buttonColors(containerColor = NixiPurple),
                            ) {
                                Text(if (enroll.recording) "Stop" else "Nagraj próbę (${enroll.takes}/3)")
                            }
                            if (enroll.done) {
                                TextButton(onClick = { enrollOpen = false }) {
                                    Text("Zamknij", color = NixiOk)
                                }
                            }
                        }
                    }
                }
            }
        }

        item { SectionTitle("Supabase") }
        item { NixiField("URL projektu", sbUrl) { sbUrl = it } }
        item { NixiField("Klucz anon", sbKey) { sbKey = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        LocalStore.supabaseUrl = sbUrl
                        LocalStore.supabaseKey = sbKey
                        SupabaseHub.rebuild()
                        SupabaseHub.refreshAll()
                        sbStatus = "Zapisano i odświeżono odkrywanie tabel."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NixiPurple),
                    shape = RoundedCornerShape(12.dp),
                    enabled = sbUrl.startsWith("http"),
                ) { Text("Zapisz i odkryj tabele") }
            }
        }
        item {
            Text(
                "Status: ${if (SupabaseHub.available) "połączono" else "brak konfiguracji"}" +
                    sbStatus,
                color = NixiTextDim, fontSize = 12.sp
            )
        }

        item { SectionTitle("Spotify") }
        item { NixiField("Client ID (developer.spotify.com)", spId) { spId = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        LocalStore.spotifyClientId = spId
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(SpotifyApi.buildAuthUrl(spId))
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            spStatus = "Otworzyłam logowanie Spotify w przeglądarce — po zatwierdzeniu powrócisz automatycznie."
                        }.onFailure { spStatus = "Błąd: ${it.message}" }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NixiPurple),
                    shape = RoundedCornerShape(12.dp),
                    enabled = spId.isNotBlank(),
                ) { Text("Połącz (kod)") }
                OutlinedButton(onClick = {
                    SpotifyApi.disconnect()
                    spStatus = "Odłączono."
                }) { Text("Odłącz", color = NixiWarn, fontSize = 12.sp) }
            }
        }
        item { Text(spStatus, color = NixiTextDim, fontSize = 12.sp) }

        item { SectionTitle("Telefon: praca w tle${if (DeviceTweaks.isXiaomi) " (Xiaomi/HyperOS)" else ""}") }
        item {
            Text(
                "Aby NIXI nie została zatrzymana po zgaszeniu ekranu, zezwól jej na pracę w tle. " +
                    "Bez tego nasłuch „Hej Nixi” i wezwanie rozmowy mogą zniknąć po kilku minutach" +
                    (if (DeviceTweaks.isXiaomi) " (HyperOS zabija aplikacje bardzo szybko)." else "."),
                color = NixiTextDim, fontSize = 11.sp,
            )
        }
        item {
            PermRow(
                "Bateria bez ograniczeń",
                batteryFree,
                openSettings = {
                    if (!DeviceTweaks.openBatterySaver(context)) {
                        runCatching { context.startActivity(DeviceTweaks.appDetails(context)) }
                    }
                }
            )
        }
        if (DeviceTweaks.isXiaomi) {
            item {
                PermRow(
                    "Autostart (wymagane po restarcie telefonu)",
                    !WakeWordService.running || batteryFree,
                    openSettings = { DeviceTweaks.openAutoStart(context) }
                )
            }
            item {
                OutlinedButton(onClick = { DeviceTweaks.openPermissionEditor(context) }) {
                    Text("Okna w tle i uprawnienia (HyperOS)", color = NixiPurple, fontSize = 12.sp)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            item {
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
                    }
                )
            }
        }

        item { SectionTitle("Uprawnienia dodatkowe") }
        item {
            PermRow(
                "Dostęp do powiadomień (odczyt + ciche reguły + pauza muzyki)",
                listenerOn,
                openSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
        }
        item {
            PermRow(
                "Usługa dostępności (sterowanie w trybie ręcznym)",
                a11yOn,
                openSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            item {
                PermRow(
                    "Dokładne alarmy (przypomnienia)",
                    exactAlarmsOk(context),
                    openSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                android.net.Uri.parse("package:" + context.packageName)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            }
        }

        item { SectionTitle("Prywatność") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { wipeConfirm = true }) {
                    Text("Wyczyść dane lokalne (klucze, szablon)", color = NixiWarn, fontSize = 12.sp)
                }
            }
        }
        item {
            Text(
                "Dane w Supabase zarządzasz na ekranie Tabele (pełny CRUD). " +
                    "Klucze API trzymają się lokalnie na urządzeniu.",
                color = NixiTextDim, fontSize = 11.sp
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    if (wipeConfirm) {
        AlertDialog(
            onDismissRequest = { wipeConfirm = false },
            containerColor = NixiSurface,
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

@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.padding(top = 10.dp, bottom = 2.dp)) {
        Text(
            text,
            color = NixiPurple,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        color = NixiSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = NixiText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun PermRow(label: String, granted: Boolean, openSettings: () -> Unit) {
    Surface(
        color = NixiSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = NixiText, fontSize = 13.sp)
                Text(
                    if (granted) "aktywnie" else "wyłączone",
                    color = if (granted) NixiOk else NixiWarn, fontSize = 11.sp
                )
            }
            if (!granted) {
                OutlinedButton(onClick = openSettings) {
                    Text("Ustaw", color = NixiPurple, fontSize = 12.sp)
                }
            }
        }
    }
}
