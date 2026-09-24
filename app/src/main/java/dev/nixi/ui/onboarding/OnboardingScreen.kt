package dev.nixi.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import dev.nixi.accessibility.NixiAccessibilityService
import dev.nixi.notif.NixiNotificationListener
import dev.nixi.store.LocalStore
import dev.nixi.ui.components.NixiOrb
import dev.nixi.ui.components.rememberOnResumeTick
import dev.nixi.util.DeviceTweaks
import dev.nixi.ui.components.PillButton
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiSurface
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.ui.theme.NixiWarn
import dev.nixi.wake.EnrollmentController

/**
 * Konfiguracja pierwszego uruchomienia — szybka, 6 prostych kroków.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { EnrollmentController.reset() }
    // Wracamy z ustawień systemowych (onboarding krok 5) — bez tego checklista
    // pokazywała stan sprzed zmiany.
    rememberOnResumeTick()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // pasek kroków
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(6) { i ->
                Box(
                    Modifier
                        .size(if (i == step) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (i <= step) NixiPurple else Color(0xFF2A2145))
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        when (step) {
            0 -> Welcome(onNext = { step = 1 })
            1 -> StepGemini(onNext = { step = 2 })
            2 -> StepSupabase(onNext = { step = 3 })
            3 -> StepWakeWord(onNext = { step = 4 })
            4 -> StepPermissions(onNext = { step = 5 })
            5 -> StepVoice(onFinish = onFinish)
        }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    NixiOrb(size = 200.dp, state = dev.nixi.NixiState.OrbState.IDLE)
    Spacer(Modifier.height(20.dp))
    Title("Cześć! Jestem NIXI")
    Text(
        "Twoja osobista asystentka AI na telefonie. Powiedz „Hej Nixi” — ja robię resztę: " +
            "muzyka, kalendarz, budziki, przypomnienia, tabele Supabase i sterowanie ekranem.",
        color = NixiTextDim, fontSize = 14.sp,
    )
    Spacer(Modifier.height(28.dp))
    PrimaryButton("Rozpocznij", onNext)
}

@Composable
private fun StepGemini(onNext: () -> Unit) {
    var key by remember { mutableStateOf(LocalStore.geminiKey) }
    Title("Mózg NIXI: Gemini Live")
    Text(
        "Wklej klucz API z Google AI Studio (aistudio.google.com/apikey). " +
            "Pracuje lokalnie w aplikacji. Model z darmowego planu (Live, 65K TPM) — " +
            "NIXI chroni limit po stronie klienta.",
        color = NixiTextDim, fontSize = 13.sp,
    )
    Spacer(Modifier.height(14.dp))
    NixiField("Klucz API Gemini", key) { key = it }
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onNext) { Text("Pomiń na razie", color = NixiTextDim) }
        Button(
            onClick = {
                LocalStore.geminiKey = key
                onNext()
            },
            enabled = key.trim().length >= 20,
            colors = ButtonDefaults.buttonColors(containerColor = NixiPurple),
        ) { Text("Dalej") }
    }
}

@Composable
private fun StepSupabase(onNext: () -> Unit) {
    var url by remember { mutableStateOf(LocalStore.supabaseUrl) }
    var apiKey by remember { mutableStateOf(LocalStore.supabaseKey) }
    var pat by remember { mutableStateOf(LocalStore.supabasePat) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Title("Pamięć: Supabase")
    Text(
        "Tutaj NIXI trzyma Twoje dane (kalendarz, budziki, pamięć…). " +
            "Skopiuj URL projektu i klucz anon z dashboardu Supabase. " +
            "Tabele zakładają się same — resztą NIXI zajmie się po zapisie.\n\n" +
            "Klucz anon nie może zmieniać struktury bazy (to zabezpieczenie Supabase). " +
            "Jeśli chcesz, żebym tworzyła tabele bez pytania Cię o cokolwiek, " +
            "wklej też token osobisty (sbp_…) z supabase.com/dashboard/account/tokens. " +
            "Bez tokenu pokażę gotowy SQL do wklejenia raz w SQL Editorze.",
        color = NixiTextDim, fontSize = 13.sp,
    )
    Spacer(Modifier.height(14.dp))
    NixiField("URL projektu (https://xyz.supabase.co)", url) { url = it }
    Spacer(Modifier.height(10.dp))
    NixiField("Klucz anon", apiKey) { apiKey = it }
    Spacer(Modifier.height(10.dp))
    NixiField("Token osobisty (opcjonalny, sbp_…)", pat) { pat = it }
    Spacer(Modifier.height(20.dp))
    if (status.isNotBlank()) {
        Text(status, color = NixiTextDim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onNext, enabled = !busy) { Text("Pomiń na razie", color = NixiTextDim) }
        Button(
            onClick = {
                LocalStore.supabaseUrl = url
                LocalStore.supabaseKey = apiKey
                LocalStore.supabasePat = pat
                dev.nixi.db.SupabaseHub.rebuild()
                dev.nixi.db.SupabaseHub.refreshAll(force = true)
                busy = true
                status = "Zakładam i aktualizuję tabele…"
                scope.launch {
                    val out = dev.nixi.db.DbProvisioner.provision()
                    status = out.message
                    if (out.needsManualSql) {
                        status = out.message + " Skopiowałam SQL do schowka."
                    }
                    busy = false
                    if (out.ok) onNext()
                }
            },
            enabled = !busy && url.startsWith("https://") && apiKey.length >= 20,
            colors = ButtonDefaults.buttonColors(containerColor = NixiPurple),
        ) { Text(if (busy) "Pracuję…" else "Dalej") }
    }
}

@Composable
private fun StepWakeWord(onNext: () -> Unit) {
    val enroll by EnrollmentController.state.collectAsState()
    Title("Wake-word: „Hej Nixi”")
    Text(
        "Wykrywanie działa OFFLINE i oszczędnie. Zarejestruj 3 razy, jak mówisz „Hej Nixi” — " +
            "tak NIXI najlepiej Cię usłyszy w tle (nawet nad blokadką).",
        color = NixiTextDim, fontSize = 13.sp,
    )
    Spacer(Modifier.height(18.dp))
    Box(
        modifier = Modifier
            .size(150.dp)
            .clip(CircleShape)
            .background(if (enroll.recording) NixiPurple else NixiSurface)
            .clickable { EnrollmentController.startAttempt() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (enroll.recording) "●\nnagrywam…" else "kliknij",
            color = if (enroll.recording) Color.White else NixiTextDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontSize = 15.sp,
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(enroll.message, color = NixiText, fontSize = 13.sp)
    if (enroll.takes > 0) {
        Spacer(Modifier.height(6.dp))
        Text("Próby: ${enroll.takes}/3", color = NixiPurple, fontSize = 12.sp)
    }
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onNext) { Text("Pomiń (dopiszę później)", color = NixiTextDim) }
        Button(
            onClick = onNext,
            enabled = enroll.done || LocalStore.wakeTemplates.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = NixiPurple),
        ) { Text("Dalej") }
    }
}

@Composable
private fun StepPermissions(onNext: () -> Unit) {
    val context = LocalContext.current
    // powrót z ustawień systemowych => świeży stan uprawnień
    val resumeTick = rememberOnResumeTick()
    var refresh by remember { mutableStateOf(0) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    val micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val notifGranted = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val listenerOn = remember(resumeTick, refresh) { NixiNotificationListener.isEnabled(context) }
    val a11yOn = remember(resumeTick, refresh) {
        NixiAccessibilityService.isAvailable() ||
            NixiAccessibilityService.isEnabledInSystem(context)
    }
    val batteryFree = remember(resumeTick) { DeviceTweaks.isIgnoringBatteryOptimizations(context) }

    Title("Uprawnienia")
    Text(
        "Potrzebne, żeby działać w tle i wykonywać zadania. Możesz je też wyłączać w każdej chwili.",
        color = NixiTextDim, fontSize = 13.sp,
    )
    Spacer(Modifier.height(16.dp))

    PermRow(
        title = "Mikrofon (wymagany)",
        desc = "Nasłuch „Hej Nixi” i rozmowy głosowe",
        granted = micGranted,
        actionLabel = "Udziel",
        onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
    )
    if (Build.VERSION.SDK_INT >= 33) {
        PermRow(
            title = "Powiadomienia",
            desc = "Krótkie raporty: co NIXI zrobiła za Tobą",
            granted = notifGranted,
            actionLabel = "Udziel",
            onAction = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + context.packageName)
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                refresh++
            },
        )
    }
    PermRow(
        title = "Dostęp do powiadomień",
        desc = "Odczyt powiadomień + ciche reguły (np. zastępstwo → kalendarz)",
        granted = listenerOn,
        actionLabel = "Otwórz ustawienia",
        onAction = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            refresh++
        },
    )
    PermRow(
        title = "Praca w tle bez ograniczeń",
        desc = if (DeviceTweaks.isXiaomi)
            "HyperOS: bateria bez ograniczeń + autostart (inaczej nasłuch padnie)"
        else "Bateria bez ograniczeń, aby nasłuch działał cały czas",
        granted = batteryFree,
        actionLabel = "Ustaw",
        onAction = {
            if (!DeviceTweaks.openBatterySaver(context)) {
                runCatching { context.startActivity(DeviceTweaks.appDetails(context)) }
            }
            refresh++
        },
    )
    val overlayOn = remember(resumeTick, refresh) {
        android.os.Build.VERSION.SDK_INT < 23 ||
            android.provider.Settings.canDrawOverlays(context)
    }
    PermRow(
        title = "Pigułka nad innymi aplikacjami",
        desc = "Żeby dało się klikać w Instagram / Chrome, gdy NIXI słucha",
        granted = overlayOn,
        actionLabel = "Udziel",
        onAction = {
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + context.packageName)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            refresh++
        },
    )
    PermRow(
        title = "Usługa dostępności (dostępna)",
        desc = "Klikanie i scrollowanie w trybie ręcznym",
        granted = a11yOn,
        actionLabel = "Otwórz ustawienia",
        onAction = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            refresh++
        },
    )
    Spacer(Modifier.height(20.dp))
    PrimaryButton("Dalej", onNext)
}

@Composable
private fun PermRow(
    title: String,
    desc: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        color = NixiSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (granted) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                tint = if (granted) NixiOk else NixiWarn,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = NixiText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(desc, color = NixiTextDim, fontSize = 12.sp)
            }
            if (!granted) {
                OutlinedButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(actionLabel, color = NixiPurple, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StepVoice(onFinish: () -> Unit) {
    val voices = listOf("Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Zephyr", "Lyncx", "Callirrhoe")
    var voice by remember { mutableStateOf(LocalStore.voiceName) }
    Title("Głos NIXI")
    Text(
        "Wybierz głos (lub wpisz dowolny nazwę głosu Gemini w Ustawieniach).",
        color = NixiTextDim, fontSize = 13.sp,
    )
    Spacer(Modifier.height(16.dp))
    FlowRowVoices(voices, voice) { voice = it }
    Spacer(Modifier.height(20.dp))
    NixiField("albo wpisz własny:", voice) { voice = it }
    Spacer(Modifier.height(24.dp))
    PrimaryButton("Zakończ i uruchom NIXI", {
        LocalStore.voiceName = voice.ifBlank { "Puck" }
        onFinish()
    })
    Spacer(Modifier.height(12.dp))
    Text(
        "W każdej chwili: Ustawienia → Nasłuch (czułość, re-rejestracja), Głos, Integracje, Tabele, Logi.",
        color = NixiTextDim, fontSize = 11.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun FlowRowVoices(voices: List<String>, selected: String, onSelect: (String) -> Unit) {
    var row by remember { mutableStateOf<List<List<String>>>(listOf()) }
    if (row.size != voices.size) {
        row = chunked(voices, selected)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEach { v ->
                    Surface(
                        color = if (v == selected) NixiPurple else NixiSurface,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.clickable { onSelect(v) },
                    ) {
                        Text(
                            v,
                            color = if (v == selected) Color.White else NixiText,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun chunked(voices: List<String>, selected: String): List<List<String>> {
    val list = voices.toMutableList()
    val out = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    var width = 0
    for (v in list) {
        val w = v.length + 6
        if (width + w > 34 && current.isNotEmpty()) {
            out.add(current)
            current = mutableListOf()
            width = 0
        }
        current.add(v)
        width += w
    }
    if (current.isNotEmpty()) out.add(current)
    return out
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        color = NixiText,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    PillButton(
        text = label,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    )
}

@Composable
fun NixiField(label: String, value: String, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            // półprzezroczyste tło: pole „siedzi" w szklanej karcie, a nie na niej
            focusedContainerColor = Color(0x66120C22),
            unfocusedContainerColor = Color(0x40120C22),
            focusedLabelColor = NixiPurple,
            unfocusedLabelColor = NixiTextDim,
            cursorColor = NixiPurple,
            focusedTextColor = NixiText,
            unfocusedTextColor = NixiText,
            focusedIndicatorColor = NixiPurple,
            unfocusedIndicatorColor = Color(0x33FFFFFF),
        ),
    )
}
