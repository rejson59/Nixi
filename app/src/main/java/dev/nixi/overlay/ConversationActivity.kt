package dev.nixi.overlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.nixi.NixiState
import dev.nixi.live.LiveSessionService
import dev.nixi.screen.ScreenCaptureService
import dev.nixi.store.LocalStore
import dev.nixi.tools.SpotifyApi
import dev.nixi.ui.components.NixiOrb
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiSurfaceHi
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.util.LogBus

/**
 * Okno rozmowy NIXI:
 *  - kula w lewym górnym rogu (nad wszystkim, także nad blokadką),
 *  - okna potwierdzeń destrukcyjnych akcji (Tak/Nie),
 *  - zgoda MediaProjection dla trybu ręcznego (dokładnie JEDEN raz),
 *  - propozycje DDL (SQL) do wklejenia.
 */
class ConversationActivity : ComponentActivity() {

    /** Znacznik czasu ostatniego pytania o zgodę (ochrona przed podwójnym dialogiem). */
    private var lastProjectionAsk = 0L

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            NixiState.manualMode.value = true
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
            LiveSessionService.instance?.onScreenConsent()
            LogBus.log("manual.consent", "tryb ręczny gotowy")
        } else {
            // brak zgody: NIXI musi o tym wiedzieć i nie może zostać w stanie „manual”
            LiveSessionService.instance?.onScreenDenied()
            LogBus.log("manual.denied", "użytkownik odmówił", "warn")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            ConversationUi(
                onClose = { finishSession() },
                onManualMode = { requestProjection() },
                onOpenApp = {
                    startActivity(
                        Intent(this, dev.nixi.ui.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            )
        }
    }

    private fun requestProjection() {
        if (ScreenCaptureService.isRunning()) {
            NixiState.manualMode.value = true
            LiveSessionService.instance?.onScreenConsent()
            return
        }
        // Kompozycja potrafi poprosić o zgodę dwa razy w tej samej klatce
        // (dwa efekty) — dlatego chronimy się krótkim oknem czasowym
        // zamiast wiecznym „już pytaliśmy”.
        val now = System.currentTimeMillis()
        if (now - lastProjectionAsk < 3000) return
        lastProjectionAsk = now
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching { projectionLauncher.launch(mpm.createScreenCaptureIntent()) }
            .onFailure {
                LogBus.log("manual.ask", "nie mogę pokazać dialogu: ${it.message}", "warn")
                LiveSessionService.instance?.onScreenDenied()
            }
    }

    /** Zamknięcie okna = grzeczny koniec sesji (sprząta mikrofon i media). */
    private fun finishSession() {
        LiveSessionService.stop(this, "użytkownik zamknął okno")
        finish()
    }
}

@Composable
fun ConversationUi(
    onClose: () -> Unit,
    onManualMode: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val context = LocalContext.current
    val state by NixiState.orb.collectAsState()
    val micLevel by NixiState.micLevel.collectAsState()
    val speakLevel by NixiState.speakLevel.collectAsState()
    val manual by NixiState.manualMode.collectAsState()
    val pending by NixiState.pendingActions.collectAsState()
    val lastTool by NixiState.lastToolLine.collectAsState()
    val pendingSql by NixiState.pendingSql.collectAsState()
    val tpm by NixiState.tpm.collectAsState()

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // JEDNA reakcja na żądanie trybu ręcznego (bez podwójnych dialogów).
    LaunchedEffect(manual) {
        if (manual && !ScreenCaptureService.isRunning()) onManualMode()
    }

    val level = when (state) {
        NixiState.OrbState.SPEAKING -> speakLevel
        NixiState.OrbState.LISTENING -> micLevel
        NixiState.OrbState.MANUAL -> micLevel
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
                .padding(
                    start = 16.dp + cutout.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    top = 24.dp + topInset,
                )
        ) {
            NixiOrb(size = 116.dp, state = state, level = level)
            Spacer(Modifier.height(6.dp))
            Text(
                "NIXI",
                color = NixiText,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.padding(start = 30.dp),
            )
            Spacer(Modifier.height(4.dp))
            StatusChip(state, lastTool)
            if (tpm.limit > 0) {
                Text(
                    "tokeny: ${tpm.used}/${tpm.limit}" +
                        if (tpm.backoffSec > 0) " • pauza ${tpm.backoffSec}s" else "",
                    color = if (tpm.percent > 90) Color(0xFFFFC46B) else NixiTextDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
            }
        }

        // ── Dolny pasek ──────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp + bottomInset),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            RoundButton(icon = Icons.Filled.TouchApp, label = "ręczny",
                onClick = onManualMode, highlighted = manual)
            RoundButton(icon = Icons.Filled.Home, label = "aplikacja", onClick = onOpenApp)
            RoundButton(icon = Icons.Filled.Close, label = "koniec",
                onClick = onClose, danger = true)
        }

        // ── Propozycja SQL (DDL) ─────────────────────────────
        AnimatedVisibility(
            visible = pendingSql.isNotBlank(),
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp + bottomInset),
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
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("nixi-sql", pendingSql))
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

    // Kod parowania Spotify (Device Flow) — tylko gdy serwer zwrócił kod.
    var spotifyCode by remember {
        mutableStateOf(SpotifyApi.deviceFlowUserCode.takeIf { it.isNotBlank() })
    }
    var spotifyMsg by remember { mutableStateOf(SpotifyApi.deviceFlowUri) }
    LaunchedEffect(Unit) {
        NixiState.events.collect { e ->
            if (e is NixiState.NixiEvent.ToolDone && e.name == "spotify_connect" && e.ok) {
                spotifyCode = SpotifyApi.deviceFlowUserCode.takeIf { it.isNotBlank() }
                spotifyMsg = SpotifyApi.deviceFlowUri
            }
        }
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
                if (spotifyMsg.isNotBlank()) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spotifyMsg)))
                        }
                    }) {
                        Text("Otwórz stronę", color = NixiPurple)
                    }
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

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
                imageVector = icon,
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
        NixiState.OrbState.TPM_LIMIT -> "limit tokenów — pauza" to Color(0xFFFFC46B)
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
