package dev.nixi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import dev.nixi.NixiState
import dev.nixi.db.SupabaseHub
import dev.nixi.store.LocalStore
import dev.nixi.tools.SpotifyApi
import dev.nixi.ui.SessionLauncher
import dev.nixi.ui.components.GlassChip
import dev.nixi.ui.components.GlassDivider
import dev.nixi.ui.components.NixiOrb
import dev.nixi.ui.components.PillButton
import dev.nixi.ui.components.ScreenHeader
import dev.nixi.ui.components.SectionCard
import dev.nixi.ui.components.StatusPill
import dev.nixi.ui.components.rememberOnResumeTick
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.ui.theme.NixiWarn
import org.json.JSONObject

/**
 * EKRAN GŁÓWNY — kula NIXI na środku, pod nią stan wszystkich usług
 * (modele, Supabase, Spotify, nasłuch) i przyciski do wywołania rozmowy.
 * Wszystko w szklanych kartach, żeby dało się to ogarnąć jednym spojrzeniem.
 */
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val state by NixiState.orb.collectAsState()
    val mic by NixiState.micLevel.collectAsState()
    val speak by NixiState.speakLevel.collectAsState()
    val wake by NixiState.wakeActive.collectAsState()
    var recent by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loadingRecent by remember { mutableStateOf(true) }
    val audioError by NixiState.lastAudioError.collectAsState()
    val inSession by NixiState.inSession.collectAsState()
    val sessionError by NixiState.lastSessionError.collectAsState()
    val checkItems by NixiState.selfCheck.collectAsState()
    val tape by dev.nixi.util.ErrorReport.items.collectAsState()
    val checkRunning by NixiState.selfCheckRunning.collectAsState()
    val tick = rememberOnResumeTick()
    val scope = rememberCoroutineScope()

    LaunchedEffect(tick) {
        val rows = SupabaseHub.recentConversations(8)
        recent = rows
        loadingRecent = false
    }

    val level = when (state) {
        NixiState.OrbState.SPEAKING -> speak
        NixiState.OrbState.LISTENING -> mic
        else -> 0f
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "NIXI",
                subtitle = "Twoja asystentka — powiedz „Hej Nixi” albo dotknij",
                trailing = {
                    StatusPill(
                        text = if (wake) "nasłuch" else "bez nasłuchu",
                        color = if (wake) NixiOk else NixiWarn,
                    )
                },
            )
        }

        item {
            // kula — serce ekranu; stan pokazuje się na pigułce pod nią
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(268.dp),
                contentAlignment = Alignment.Center,
            ) {
                NixiOrb(size = 224.dp, state = state, level = level)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                StatusPill(state = state)
            }
        }

        item {
            PillButton(
                text = "Rozmów",
                icon = Icons.Filled.Chat,
                modifier = Modifier.fillMaxWidth(),
                onClick = { SessionLauncher.start(context, "button") },
            )
        }

        if (tape.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Do zgłoszenia",
                    subtitle = "Skopiuj i wklej agentowi — to ostatnie prawdziwe błędy, nie ostrzeżenia.",
                    accent = NixiWarn,
                ) {
                    Text(
                        tape.first().let { "${it.where}: ${it.what}" },
                        color = NixiText, fontSize = 13.sp, maxLines = 4,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(
                            text = "Kopiuj raport",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "nixi-raport",
                                        dev.nixi.util.ErrorReport.snapshot(),
                                    )
                                )
                            },
                        )
                        PillButton(
                            text = "Wyczyść",
                            filled = false,
                            onClick = { dev.nixi.util.ErrorReport.clear() },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Rozmowa z Gemini",
                subtitle = if (inSession) "Sesja trwa." else "Kula albo „Hej Nixi” — resztą zajmuje się ona.",
                accent = when {
                    sessionError.isNotBlank() -> dev.nixi.ui.theme.NixiErr
                    checkItems.isEmpty() -> NixiPurple
                    checkItems.any { it.level == dev.nixi.util.SelfCheck.Level.ERR } ->
                        dev.nixi.ui.theme.NixiWarn
                    else -> NixiOk
                },
            ) {
                if (sessionError.isNotBlank() && !inSession) {
                    Text(
                        "Ostatnia próba rozmowy nie udała się:",
                        color = NixiTextDim, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(sessionError, color = NixiWarn, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                }
                // Co się stało w ostatniej rozmowie — suche fakty, żeby
                // „nie odpowiada” miało od razu jakiś konkret.
                val lastReason = remember(tick) { LocalStore.lastSessionReason }
                if (lastReason.isNotBlank() && !inSession) {
                    Text(
                        "Ostatnia rozmowa: " + lastReason +
                            " • ${LocalStore.lastSessionDuration}s" +
                            " • klatki audio: ${LocalStore.lastSessionAudioChunks}" +
                            " • odpowiedzi: ${LocalStore.lastSessionAudioReplies}" +
                            " • wariant ${LocalStore.lastSessionVariant}" +
                            (if (LocalStore.lastSessionSetupOk) "" else " (sesja niepotwierdzona)"),
                        color = NixiTextDim, fontSize = 11.sp, maxLines = 3,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                PillButton(
                    text = if (checkRunning) "Sprawdzam…" else "Sprawdź NIXI",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            dev.nixi.util.SelfCheck.run(context)
                        }
                    },
                )
                if (checkItems.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    checkItems.forEachIndexed { index, item ->
                        if (index > 0) {
                            Spacer(Modifier.height(8.dp))
                            GlassDivider()
                            Spacer(Modifier.height(8.dp))
                        }
                        val color = when (item.level) {
                            dev.nixi.util.SelfCheck.Level.OK -> NixiOk
                            dev.nixi.util.SelfCheck.Level.WARN -> NixiWarn
                            dev.nixi.util.SelfCheck.Level.ERR -> dev.nixi.ui.theme.NixiErr
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier
                                    .padding(top = 6.dp)
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.label, color = NixiText, fontSize = 13.sp)
                                Text(item.detail, color = NixiTextDim, fontSize = 11.sp)
                                if (item.fix.isNotBlank()) {
                                    Text(item.fix, color = NixiPurple, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Jedno tapnięcie i NIXI sama sprawdzi klucz API, nazwę modelu, " +
                            "mikrofon, nasłuch, bazę i uprawnienia — i powie, co poprawić.",
                        color = NixiTextDim, fontSize = 11.sp,
                    )
                }
            }
        }

        if (audioError.isNotBlank()) {
            item {
                SectionCard(
                    title = "Problem z mikrofonem",
                    accent = NixiWarn,
                ) {
                    Text(audioError, color = NixiTextDim, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Jeśli to zajęty mikrofon (rozmowa, dyktafon), NIXI wznowi nasłuch sama.",
                        color = NixiTextDim, fontSize = 11.sp,
                    )
                }
            }
        }

        item {
            if (!LocalStore.wakeEnabled) {
                SectionCard(title = "Nasłuch wyłączony", accent = NixiWarn) {
                    Text(
                        "„Hej Nixi” nie działa — włącz nasłuch w Ustawieniach → Nasłuch, " +
                            "żeby NIXI reagowała na głos.",
                        color = NixiTextDim, fontSize = 12.sp,
                    )
                }
            } else if (LocalStore.wakeNeedsEnroll) {
                SectionCard(title = "Nagraj wzorzec jeszcze raz", accent = NixiWarn) {
                    Text(
                        "Detektor „Hej Nixi” został przebudowany i nie rozumie starego " +
                            "wzorca. Nagraj frazę od nowa (3 próby) w Ustawieniach → Nasłuch.",
                        color = NixiTextDim, fontSize = 12.sp,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Stan") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(
                        text = if (wake) "nasłuch" else "cisza",
                        dot = if (wake) NixiOk else NixiWarn,
                    )
                    GlassChip(
                        text = if (SupabaseHub.available) "pamięć" else "brak bazy",
                        dot = if (SupabaseHub.available) NixiOk else NixiTextDim,
                    )
                    if (SpotifyApi.isConnected()) {
                        GlassChip("Spotify", dot = NixiOk)
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Ostatnie rozmowy",
                subtitle = "Krótkie podsumowania zapisane w Supabase.",
            ) {
                if (loadingRecent) {
                    Text("Ładowanie…", color = NixiTextDim, fontSize = 13.sp)
                } else if (recent.isEmpty()) {
                    Text(
                        "Jeszcze nic tu nie ma — po kilku rozmowach NIXI zostawi tu " +
                            "krótkie podsumowania.",
                        color = NixiTextDim, fontSize = 13.sp,
                    )
                } else {
                    recent.forEachIndexed { index, r ->
                        if (index > 0) {
                            Spacer(Modifier.height(10.dp))
                            GlassDivider()
                            Spacer(Modifier.height(10.dp))
                        }
                        Text(
                            r.optString("summary", "—"),
                            color = NixiText, fontSize = 13.sp,
                        )
                        val topics = r.optString("topics", "")
                        if (topics.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(topics, color = NixiTextDim, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}
