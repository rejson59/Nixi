package dev.nixi.overlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NorthEast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.media.MediaProjectionManager
import androidx.core.view.WindowCompat
import dev.nixi.NixiState
import dev.nixi.R
import dev.nixi.live.LiveSessionService
import dev.nixi.screen.ScreenCaptureService
import dev.nixi.store.LocalStore
import dev.nixi.tools.SpotifyApi
import dev.nixi.ui.components.NixiOrb
import dev.nixi.ui.theme.NixiBg
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiSurfaceHi
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import kotlinx.coroutines.launch

/**
 * Okno rozmowy NIXI:
 *  - kula slide-in w lewym górnym rogu (nad wszystkim, także nad blokadką),
 *  - okna potwierdzeń destrukcyjnych akcji (Tak/Nie),
 *  - konsent MediaProjection + start trybu ręcznego,
 *  - kod parowania Spotify,
 *  - propozycje DDL (SQL) do wklejenia.
 */
class ConversationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWhenLocked(true)
        setTurnScreenOn(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                if (data != null) {
                    ScreenCaptureService.start(this, result.resultCode, data)
                    LiveSessionService.instance?.onScreenConsent()
                }
            }
        }

        setContent {
            ConversationUi(
                onClose = { finish() },
                onManualMode = {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    NixiState.manualMode.value = true
                    runCatching {
                        projectionLauncher.launch(mpm.createScreenCaptureIntent())
                    }
                },
                onOpenApp = {
                    startActivity(
                        Intent(this, dev.nixi.ui.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@Composable
fun ConversationUi(
    onClose: () -> Unit,
    onManualMode: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by NixiState.orb.collectAsState()
    val micLevel by NixiState.micLevel.collectAsState()
    val speakLevel by NixiState.speakLevel.collectAsState()
    val manual by NixiState.manualMode.collectAsState()
    val pending by NixiState.pendingActions.collectAsState()
    val lastTool by NixiState.lastToolLine.collectAsState()
    val pendingSql by NixiState.pendingSql.collectAsState()

    // kod Spotify (z narzędzia spotify_connect)
    var spotifyCode by remember { mutableStateOf<String?>(null) }
    var spotifyMsg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        NixiState.events.collect { e ->
            when (e) {
                is NixiState.NixiEvent.ToolDone ->
                    if (e.name == "spotify_connect" && e.ok) {
                        spotifyCode = SpotifyApi.deviceFlowUserCode
                        spotifyMsg = SpotifyApi.deviceFlowUri
                    }
                else -> Unit
            }
        }
    }
    // po znalezieniu kodu: automatyczny polling
    LaunchedEffect(spotifyCode) {
        spotifyCode ?: return@LaunchedEffect
        val result = SpotifyApi.pollDeviceFlow()
        spotifyCode = null
        dev.nixi.notif.ActionNotifier.notify(context, "NIXI: Spotify", result, short = true)
    }

    // automatyczne poproszenie o podgląd ekranu (tryb ręczny bez zgody)
    var askingScreen by remember { mutableStateOf(false) }
    LaunchedEffect(manual) {
        if (manual && !ScreenCaptureService.isRunning() && !askingScreen) {
            askingScreen = true
            onManualMode()
            dev.nixi.util.LogBus.log("manual.ask", "konsent MediaProjection")
            kotlinx.coroutines.delay(4000)
            askingScreen = false
        }
    }

    val level = when (state) {
        NixiState.OrbState.SPEAKING -> speakLevel
        NixiState.OrbState.LISTENING -> micLevel
        else -> 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE60A0613))
    ) {
        // ── Kula + status ────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 40.dp)
        ) {
            NixiOrb(
                size = 128.dp,
                state = state,
                level = level,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "NIXI",
                color = NixiText,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.padding(start = 34.dp),
            )
            Spacer(Modifier.height(4.dp))
            StatusChip(state, lastTool)
        }

        // ── Dolny pasek ──────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            RoundButton(icon = { Icon(Icons.Filled.TouchApp, null) }, label = "ręczny",
                onClick = onManualMode, highlighted = manual)
            RoundButton(icon = { Icon(Icons.Filled.Home, null) }, label = "aplikacja",
                onClick = onOpenApp)
            RoundButton(icon = { Icon(Icons.Filled.Close, null) }, label = "koniec",
                onClick = onClose, highlighted = false, danger = true)
        }

        // ── Propozycja SQL (DDL) ─────────────────────────────
        AnimatedVisibility(
            visible = pendingSql.isNotBlank(),
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 108.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = NixiSurfaceHi,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("NIXI zaproponowała zmianę struktury (DDL)",
                        color = NixiText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            pendingSql,
                            color = NixiTextDim,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .height(120.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            android.content.ClipboardManagerCompat.set(
                                context, pendingSql
                            )
                        }) {
                            Icon(Icons.Filled.ContentCopy, null, tint = NixiPurple)
                            Spacer(Modifier.width(6.dp))
                            Text("Kopiuj SQL", color = NixiPurple)
                        }
                        OutlinedButton(onClick = {
                            val ref = supabaseProjectRef()
                            if (ref.isNotBlank()) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW,
                                            Uri.parse("https://supabase.com/dashboard/project/$ref/sql/new"))
                                    )
                                }
                            }
                        }) {
                            Text("Otwórz SQL Editor", color = NixiPurple)
                        }
                        TextButton(onClick = { NixiState.pendingSql.value = "" }) {
                            Text("Zamknij", color = NixiTextDim)
                        }
                    }
                }
            }
        }
    }

    // ── Diálogi ────────────────────────────────────────────
    pending.firstOrNull()?.let { p ->
        AlertDialog(
            onDismissRequest = { LiveSessionService.confirm(p.id, false) },
            containerColor = NixiSurfaceHi,
            title = { Text(p.title, color = NixiText) },
            text = { Text(p.detail, color = NixiTextDim) },
            confirmButton = {
                TextButton(onClick = { LiveSessionService.confirm(p.id, true) }) {
                    Text("Tak, wykonaj", color = NixiOk)
                }
            },
            dismissButton = {
                TextButton(onClick = { LiveSessionService.confirm(p.id, false) }) {
                    Text("Nie", color = NixiTextDim)
                }
            },
        )
    }

    if (spotifyCode != null) {
        AlertDialog(
            onDismissRequest = { spotifyCode = null },
            containerColor = NixiSurfaceHi,
            title = { Text("Parowanie Spotify", color = NixiText) },
            text = {
                Column {
                    Text("W przeglądarce zatwierdź kod:", color = NixiTextDim)
                    Spacer(Modifier.height(8.dp))
                    Text(spotifyCode ?: "", color = NixiPurple, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Automatycznie sprawdzę co ~5 s (do 5 min).",
                        color = NixiTextDim, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spotifyMsg)))
                    }
                }) {
                    Text("Otwórz stronę", color = NixiPurple)
                }
            },
            dismissButton = {
                TextButton(onClick = { spotifyCode = null }) { Text("Zamknij", color = NixiTextDim) }
            },
        )
    }
}

private fun supabaseProjectRef(): String {
    return try {
        val url = LocalStore.supabaseUrl
        val host = Uri.parse(url).host.orEmpty() // "xyz.supabase.co"
        host.substringBefore('.')
    } catch (_: Exception) {
        ""
    }
}

private object ClipboardManagerCompat {
    fun set(context: android.content.Context, text: String) {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("nixi-sql", text))
        dev.nixi.util.LogBus.log("sql.copy", "skopiowano do schowka")
    }
}

@Composable
private fun RoundButton(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    danger: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    when {
                        danger -> Color(0xFF3A1020)
                        highlighted -> NixiPurple
                        else -> Color(0x99171029)
                    }
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when {
                    danger -> Icons.Filled.Close
                    highlighted -> Icons.Filled.TouchApp
                    else -> Icons.Filled.Home
                },
                contentDescription = label,
                tint = if (highlighted) Color.White else NixiText,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = NixiTextDim, fontSize = 11.sp)
    }
}

@Composable
private fun StatusChip(state: NixiState.OrbState, lastTool: String) {
    val (text, color) = when (state) {
        NixiState.OrbState.IDLE -> "gotowa" to NixiTextDim
        NixiState.OrbState.LISTENING -> "słucham…" to NixiOk
        NixiState.OrbState.THINKING -> "myślę…" to NixiPurple
        NixiState.OrbState.SPEAKING -> "odpowiadam" to NixiPurple
        NixiState.OrbState.MANUAL -> "tryb ręczny — steruję ekranem" to NixiPurple
        NixiState.OrbState.TPM_LIMIT -> "limit tokeni — pauza" to Color(0xFFFFC46B)
        NixiState.OrbState.ERROR -> "błąd" to Color(0xFFFF7A7A)
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color(0xB3171029),
        modifier = Modifier.padding(start = 12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                color = NixiText,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
    if (lastTool.isNotBlank()) {
        Text(
            "» " + lastTool,
            color = NixiTextDim,
            fontSize = 11.sp,
            maxLines = 2,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp),
        )
    }
}
