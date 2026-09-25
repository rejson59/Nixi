package dev.nixi.ui.logs

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.ui.theme.NixiWarn
import dev.nixi.ui.components.GlassTabs
import dev.nixi.ui.components.PillButton
import dev.nixi.ui.components.ScreenHeader
import dev.nixi.ui.components.SectionCard
import dev.nixi.ui.components.glass
import dev.nixi.util.LogBus
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logi systemowe (system_logs) + błędy (errors) — z bazy i z bufora lokalnego. */
@Composable
fun LogsScreen() {
    var filter by remember { mutableStateOf("all") }
    var dbRows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var dbReady by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(LogBus.tail(100)) }
    var tick by remember { mutableStateOf(0) }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale("pl")) }

    // Ładowanie TYLKO gdy zmieni się filtr albo gdy użytkownik odświeży —
    // wcześniej `load()` stało w ciele kompozycji i strzelało siecią przy
    // każdym przeliczeniu (pętla zapytań).
    LaunchedEffect(filter, tick) {
        local = LogBus.tail(100)
        dbReady = false
        val rows = if (filter == "errors") SupabaseHub.recentLogs(100, Tables.ERRORS)
        else SupabaseHub.recentLogs(100, Tables.LOGS)
        dbRows = rows
        dbReady = true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(
                title = "Logi",
                subtitle = "Co NIXI robiła i co się nie udało — z bazy i z pamięci.",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(
                            text = "Kopiuj raport",
                            filled = false,
                            onClick = {
                                val ctx = dev.nixi.NixiApp.ctx()
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "nixi-raport",
                                        dev.nixi.util.ErrorReport.snapshot(),
                                    )
                                )
                            },
                        )
                        PillButton(text = "Odśwież", filled = false, onClick = { tick++ })
                    }
                },
            )
        }
        item {
            val filterLabels = listOf("Wszystkie", "Akcje", "Błędy")
            val filterValues = listOf("all", "system", "errors")
            GlassTabs(
                labels = filterLabels,
                selected = filterValues.indexOf(filter).coerceAtLeast(0),
                onSelect = { filter = filterValues[it] },
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        if (dbReady && dbRows.isEmpty()) {
            item {
                SectionCard(
                    title = "Brak wpisów w bazie",
                    subtitle = "Supabase niepołączony albo tabele są jeszcze puste.",
                ) {
                    Text(
                        "Poniżej pokazuję bufor lokalny — ostatnie wpisy z pamięci telefonu.",
                        color = NixiTextDim, fontSize = 12.sp,
                    )
                }
            }
        }

        if (dbReady) {
            items(dbRows) { r ->
                val isErr = filter == "errors" || r.optString("status") == "error"
                val ts = r.optLong("ts", 0L)
                val action = if (isErr) r.optString("tag", "błąd") else r.optString("action", "—")
                val detail = r.optString("detail", r.optString("message", ""))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(shape = RoundedCornerShape(12.dp))
                        .then(
                            if (isErr) Modifier.background(
                                Color(0x33FF7A7A), RoundedCornerShape(12.dp)
                            ) else Modifier
                        )
                        .padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            if (ts > 0) fmt.format(Date(ts)) else "—",
                            color = NixiTextDim, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(86.dp),
                        )
                        Column {
                            Text(
                                action,
                                color = if (isErr) NixiWarn else NixiOk,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (detail.isNotBlank()) {
                                Text(
                                    detail,
                                    color = NixiTextDim,
                                    fontSize = 11.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (local.isNotEmpty()) {
            item {
                Text(
                    "Bufor lokalny (ostatnie wpisy w pamięci)",
                    color = NixiPurple,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            items(local) { e ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(shape = RoundedCornerShape(12.dp))
                        .padding(10.dp),
                ) {
                    Row {
                        Text(
                            fmt.format(Date(e.ts)),
                            color = NixiTextDim, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(86.dp),
                        )
                        Column {
                            Text(
                                e.action,
                                color = when (e.status) {
                                    "error" -> NixiWarn
                                    "warn" -> NixiWarn
                                    else -> NixiOk
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (e.detail.isNotBlank()) {
                                Text(e.detail, color = NixiTextDim, fontSize = 11.sp, maxLines = 3)
                            }
                        }
                    }
                }
            }
        }
        item {
            PillButton(
                text = "Wyczyść bufor lokalny",
                filled = false,
                onClick = { LogBus.clear() },
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
