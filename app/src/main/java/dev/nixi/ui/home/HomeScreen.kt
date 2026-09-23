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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nixi.NixiState
import dev.nixi.db.SupabaseHub
import dev.nixi.store.LocalStore
import dev.nixi.tools.SpotifyApi
import dev.nixi.ui.SessionLauncher
import dev.nixi.ui.components.NixiOrb
import dev.nixi.ui.theme.NixiBg
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiSurface
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.ui.theme.NixiWarn
import org.json.JSONObject

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val state by NixiState.orb.collectAsState()
    val mic by NixiState.micLevel.collectAsState()
    val speak by NixiState.speakLevel.collectAsState()
    val wake by NixiState.wakeActive.collectAsState()
    var recent by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loadingRecent by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
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
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NIXI", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = NixiText)
                Spacer(Modifier.width(12.dp))
                StatusDot(
                    text = if (wake) "nasłuch aktywny" else "nasłuch wyłączony",
                    color = if (wake) NixiOk else NixiWarn,
                )
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center,
            ) {
                NixiOrb(size = 230.dp, state = state, level = level)
            }
        }

        item {
            // statusy
            val sbOn = SupabaseHub.available
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoChip("model: ${LocalStore.geminiModel}", Modifier.weight(1f))
                InfoChip("Supabase: ${if (sbOn) "połączono" else "brak"}", Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoChip(
                    "Spotify: ${if (SpotifyApi.isConnected()) "połączono" else "brak"}",
                    Modifier.weight(1f)
                )
                InfoChip(
                    "nasłuch CPU: ${LocalStore.wakeCpuMsPerMin} ms/min",
                    Modifier.weight(1f)
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { SessionLauncher.start(context, "button") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = NixiPurple),
                ) {
                    Text("Rozmów", color = Color.White)
                }
                OutlinedButton(
                    onClick = { SessionLauncher.start(context, "manual", manual = true) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Tryb ręczny", color = NixiPurple)
                }
            }
        }

        item {
            Text(
                "Ostatnie ciekawe rozmowy",
                color = NixiText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }

        if (loadingRecent) {
            item { Text("Ładowanie…", color = NixiTextDim, fontSize = 13.sp) }
        } else if (recent.isEmpty()) {
            item {
                Text(
                    "Jeszcze nic tu nie ma — po kilku rozmowach NIXI będzie tu zostawiać krótkie podsumowania.",
                    color = NixiTextDim, fontSize = 13.sp,
                )
            }
        } else {
            items(recent) { r ->
                Surface(
                    color = NixiSurface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            r.optString("summary", "—"),
                            color = NixiText, fontSize = 13.sp,
                        )
                        val topics = r.optString("topics", "")
                        if (topics.isNotBlank()) {
                            Text(topics, color = NixiTextDim, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun StatusDot(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = NixiTextDim, fontSize = 12.sp)
    }
}

@Composable
private fun InfoChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = NixiSurface,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Text(
            text,
            color = NixiTextDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            textAlign = TextAlign.Start,
        )
    }
}
