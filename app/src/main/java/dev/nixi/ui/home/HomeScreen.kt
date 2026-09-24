package dev.nixi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val tpm by NixiState.tpm.collectAsState()
    val audioError by NixiState.lastAudioError.collectAsState()
    val inSession by NixiState.inSession.collectAsState()
    val tick = rememberOnResumeTick()

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PillButton(
                    text = "Rozmów",
                    icon = Icons.Filled.Chat,
                    modifier = Modifier.weight(1f),
                    onClick = { SessionLauncher.start(context, "button") },
                )
                PillButton(
                    text = "Tryb ręczny",
                    icon = Icons.Filled.TouchApp,
                    filled = false,
                    modifier = Modifier.weight(1f),
                    onClick = { SessionLauncher.start(context, "manual", manual = true) },
                )
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
            SectionCard(
                title = "Stan NIXI",
                subtitle = "Skrót tego, co widzi asystentka.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("model: ${LocalStore.geminiModel}", Modifier.weight(1f))
                    GlassChip(
                        text = "Supabase: ${if (SupabaseHub.available) "połączono" else "brak"}",
                        dot = if (SupabaseHub.available) NixiOk else NixiTextDim,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(
                        text = "Spotify: ${if (SpotifyApi.isConnected()) "połączono" else "brak"}",
                        dot = if (SpotifyApi.isConnected()) NixiOk else NixiTextDim,
                        modifier = Modifier.weight(1f),
                    )
                    GlassChip("nasłuch: ${LocalStore.wakeCpuMsPerMin} ms/min", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(
                        text = if (inSession) "sesja: trwa" else "sesja: brak",
                        dot = if (inSession) NixiOk else NixiTextDim,
                        modifier = Modifier.weight(1f),
                    )
                    GlassChip(
                        text = if (tpm.limit > 0) "tokeny: ${tpm.used}/${tpm.limit}" else "tokeny: —",
                        modifier = Modifier.weight(1f),
                    )
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
