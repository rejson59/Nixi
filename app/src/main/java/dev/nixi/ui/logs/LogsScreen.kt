package dev.nixi.ui.logs

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import dev.nixi.ui.theme.NixiBg
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiSurface
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.ui.theme.NixiWarn
import dev.nixi.util.LogBus
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logi systemowe (system_logs) + błędy (errors) — z bazy i z bufora lokalnego. */
@Composable
fun LogsScreen() {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    var dbRows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var dbReady by remember { mutableStateOf(false) }
    val local = remember { LogBus.tail(100) }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale("pl")) }

    fun load() {
        scope.launch {
            val rows = if (filter == "errors") SupabaseHub.recentLogs(100, Tables.ERRORS)
            else SupabaseHub.recentLogs(100, Tables.LOGS)
            dbRows = rows
            dbReady = true
        }
    }
    load()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Logi", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NixiText)
                Spacer(Modifier.width(10.dp))
                listOf("all" to "Wszystkie", "system" to "Akcje", "errors" to "Błędy").forEach { (v, l) ->
                    val active = filter == v
                    Surface(
                        color = if (active) NixiPurple else NixiSurface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(start = 6.dp),
                    ) {
                        Box {
                            Text(
                                l,
                                color = if (active) Color.White else NixiText,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .androidxComposeClick { filter = v; load() },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { load() }) {
                    Text("Odśwież", color = NixiPurple, fontSize = 12.sp)
                }
            }
        }

        if (dbReady && dbRows.isEmpty()) {
            item {
                Text(
                    "Pusta baza (lub Supabase niepołączony). Poniżej bufor lokalny (ostatnie sesje).",
                    color = NixiTextDim, fontSize = 12.sp,
                )
            }
        }

        if (dbReady) {
            items(dbRows) { r ->
                val isErr = filter == "errors" || r.optString("status") == "error"
                val ts = r.optLong("ts", 0L)
                val action = if (isErr) r.optString("tag", "błąd") else r.optString("action", "—")
                val detail = r.optString("detail", r.optString("message", ""))
                Surface(
                    color = if (isErr) Color(0xFF2A1220) else NixiSurface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
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
                    "Bufer lokalny (ostatnie wpisy w pamięci)",
                    color = NixiPurple,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            items(local) { e ->
                Surface(
                    color = NixiSurface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(10.dp)) {
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
            Button(
                onClick = { LogBus.clear() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A1220)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Wyczyść bufor lokalny", color = NixiWarn, fontSize = 12.sp)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun Modifier.androidxComposeClick(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))
