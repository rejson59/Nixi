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
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
import dev.nixi.ui.components.GlassDivider
import dev.nixi.ui.components.GlassIconButton
import dev.nixi.ui.components.GlassPill
import dev.nixi.ui.components.NixiOrb
import dev.nixi.ui.components.StatusPill
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiSurfaceHi
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.ui.theme.NixiWarn
import dev.nixi.util.LogBus
import kotlinx.coroutines.delay

/**
 * Okno rozmowy NIXI — JEDNA szklana pigułka u góry ekranu.
 *
 * W pigułce jest wszystko, co dotyczy wywołania asystentki: kula (stan i
 * poziom głosu), nazwa ze statusem, ostatnie narzędzie, licznik tokenów oraz
 * sterowanie (tryb ręczny / aplikacja / koniec). Nie ma osobnego paska na
 * dole ekranu ani żadnego przyciemnienia — tło (Twoja aplikacja, film, cokolwiek
 * masz pod spodem) zostaje widoczne, a czytelność zapewnia samo szkło.
 *
 * Pigułka wjeżdża z góry ekranu (slide-in + delikatne powiększenie kuli),
 * a przy zamykaniu okna chowa się tą samą drogą, zanim aktywność zniknie.
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

        // Okno TYLKO na wysokość pigułki — reszta ekranu nie łapie kliknięć.
        runCatching {
            window.setDimAmount(0f)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val lp = window.attributes
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            lp.flags = lp.flags or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            window.attributes = lp
            window.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }

        if (intent?.getBooleanExtra("ask_projection", false) == true) {
            requestProjection()
        }

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
    val lastHeard by NixiState.lastHeard.collectAsState()
    val lastSaid by NixiState.lastSaid.collectAsState()
    val sessionError by NixiState.lastSessionError.collectAsState()
    val pendingSql by NixiState.pendingSql.collectAsState()
    val tpm by NixiState.tpm.collectAsState()

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val wantCapture by NixiState.wantScreenCapture.collectAsState()
    // JEDNA reakcja na żądanie trybu ręcznego (bez podwójnych dialogów).
    LaunchedEffect(manual, wantCapture) {
        if ((manual || wantCapture) && !ScreenCaptureService.isRunning()) onManualMode()
    }

    val level = when (state) {
        NixiState.OrbState.SPEAKING -> speakLevel
        NixiState.OrbState.LISTENING -> micLevel
        NixiState.OrbState.MANUAL -> micLevel
        else -> 0f
    }

    // ── Wjazd z góry ──────────────────────────────────────────────────────
    // Okno pojawia się natychmiast (tak działa start aktywności z tła), ale
    // pigułka wjeżdża z góry ekranu, więc wrażenie jest płynne, a nie skokowe.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // jedna klatka opóźnienia, żeby animacja miała od czego wystartować
        delay(16)
        shown = true
    }
    // Zamknięcie z animacją: najpierw pigułka chowa się do góry (220 ms),
    // dopiero potem kończymy aktywność — inaczej okno znikałoby skokowo.
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(closing) {
        if (closing) {
            delay(220)
            onClose()
        }
    }
    val orbScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.82f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "orbScale",
    )

    val cutoutTop = cutout.calculateTopPadding()
    val pillTop = (if (cutoutTop > topInset) cutoutTop else topInset) + 10.dp

    Column(modifier = Modifier.fillMaxWidth()) {
        // ── JEDNA pigułka: kula + status + sterowanie ─────────────────────
        AnimatedVisibility(
            visible = shown && !closing,
            enter = slideInVertically(
                initialOffsetY = { -it - 24 },
                animationSpec = tween(360, easing = LinearOutSlowInEasing),
            ) + fadeIn(tween(260)),
            exit = slideOutVertically(
                targetOffsetY = { -it - 24 },
                animationSpec = tween(220),
            ) + fadeOut(tween(180)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            GlassPill(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = pillTop, start = 12.dp, end = 12.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Kula w pigułce — mniejsza, żeby całość była zgrabna
                        Box(modifier = Modifier.scale(orbScale)) {
                            NixiOrb(size = 72.dp, state = state, level = level)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "NIXI",
                                    color = NixiText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                )
                                Spacer(Modifier.width(10.dp))
                                StatusPill(state = state)
                            }
                            if (lastHeard.isNotBlank() || lastSaid.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                if (lastHeard.isNotBlank()) {
                                    Text("Ty: $lastHeard", color = NixiTextDim, fontSize = 11.sp, maxLines = 1)
                                }
                                if (lastSaid.isNotBlank()) {
                                    Text("NIXI: $lastSaid", color = NixiTextDim, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                            if (lastTool.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "» " + lastTool,
                                    color = NixiTextDim,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                )
                            }
                            if (sessionError.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "⚠ " + sessionError,
                                    color = NixiWarn,
                                    fontSize = 11.sp,
                                    maxLines = 3,
                                )
                            }
                            if (tpm.limit > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "tokeny: ${tpm.used}/${tpm.limit}" +
                                        if (tpm.backoffSec > 0) " • pauza ${tpm.backoffSec}s" else "",
                                    color = if (tpm.percent > 90) NixiWarn else NixiTextDim,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    GlassDivider()
                    Spacer(Modifier.height(10.dp))

                    // Sterowanie w tej samej pigułce (wcześniej: osobny pasek na dole)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        GlassIconButton(
                            icon = Icons.Filled.TouchApp,
                            label = "ręczny",
                            size = 44.dp,
                            accent = if (manual) NixiPurple else null,
                            onClick = onManualMode,
                        )
                        GlassIconButton(
                            icon = Icons.Filled.Home,
                            label = "aplikacja",
                            size = 44.dp,
                            onClick = onOpenApp,
                        )
                        GlassIconButton(
                            icon = Icons.Filled.Close,
                            label = "koniec",
                            size = 44.dp,
                            accent = Color(0xFF7A2C3F),
                            onClick = { closing = true },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Zakończ rozmowę",
                        color = NixiWarn,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { closing = true }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }

        // ── Propozycja SQL (DDL) — rzadka, więc osobny szklany panel pod pigułką ──
        AnimatedVisibility(
            visible = pendingSql.isNotBlank(),
            enter = slideInVertically(
                initialOffsetY = { -it / 2 },
                animationSpec = tween(280),
            ) + fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(280)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp + bottomInset),
        ) {
            GlassPill(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(Modifier.padding(4.dp)) {
                    Text(
                        "NIXI zaproponowała zmianę struktury (DDL)",
                        color = NixiText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    )
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
                        TextButton(onClick = {
                            val ref = supabaseProjectRef()
                            if (ref.isNotBlank()) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://supabase.com/dashboard/project/$ref/sql/new")
                                        )
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
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { LiveSessionService.confirm(p.id, true) }) {
                        Text("Tak, wykonaj", color = NixiOk)
                    }
                    TextButton(onClick = {
                        NixiState.sessionTrusted.value = true
                        LiveSessionService.confirm(p.id, true)
                    }) {
                        Text("Tak, i nie pytaj już", color = NixiPurple)
                    }
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
