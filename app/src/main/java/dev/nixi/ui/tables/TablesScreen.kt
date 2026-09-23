package dev.nixi.ui.tables

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nixi.NixiState
import dev.nixi.db.DiscoveredTables
import dev.nixi.db.PostgrestClient
import dev.nixi.db.SupabaseHub
import dev.nixi.db.Tables
import dev.nixi.ui.onboarding.NixiField
import dev.nixi.ui.theme.NixiBg
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiSurface
import dev.nixi.ui.theme.NixiSurfaceHi
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.ui.theme.NixiWarn
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Pełny przegląd tabel Supabase: odczyt / edycja / dodawanie / usuwanie
 * wierszy + zarządzanie dostępem NIXI (nixi_access) + propozycje DDL.
 */
@Composable
fun TablesScreen() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tables by DiscoveredTables.value.collectAsState()
    val pendingSql by NixiState.pendingSql.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var known by remember { mutableStateOf(dev.nixi.store.LocalStore.knownTableList()) }
    var newTableDlg by remember { mutableStateOf(false) }
    var addColDlg by remember { mutableStateOf(false) }
    var dlgTable by remember { mutableStateOf("") }
    var dlgColDef by remember { mutableStateOf("") }
    var dlgTableName by remember { mutableStateOf("") }
    var dlgNewCols by remember { mutableStateOf("") }

    val allTables: List<String> = remember(tables, known) {
        val set = LinkedHashSet<String>()
        tables.map { it.name }.forEach { set.add(it) }
        known.forEach { set.add(it) }
        set.toList()
    }

    if (selected != null) {
        TableDetailScreen(
            table = selected!!,
            onBack = { selected = null },
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tabele", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NixiText)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (tables.isNotEmpty()) "${allTables.size} (odkryto: ${tables.size})"
                    else "brak odkrycia — użyj przycisku poniżej",
                    color = NixiTextDim, fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = {
                    scope.launch {
                        if (SupabaseHub.available) {
                            SupabaseHub.refreshAll()
                        }
                    }
                }) { Text("Odkryj ponownie", color = NixiPurple, fontSize = 12.sp) }
            }
        }
        if (!SupabaseHub.available) {
            item {
                Text(
                    "Supabase nie jest skonfigurowany — ustaw URL i klucz w Ustawieniach. " +
                        "Lista poniżej to znane tabele NIXI.",
                    color = NixiWarn, fontSize = 12.sp,
                )
            }
        }
        if (pendingSql.isNotBlank()) {
            item {
                SqlProposalCard(pendingSql) {
                    NixiState.pendingSql.value = ""
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { newTableDlg = true }) {
                    Text("Nowa tabela (DDL)", color = NixiPurple, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { addColDlg = true }) {
                    Text("Nowa kolumna (DDL)", color = NixiPurple, fontSize = 12.sp)
                }
            }
        }

        // dialogi DDL
        if (newTableDlg) {
            AlertDialog(
                onDismissRequest = { newTableDlg = false },
                containerColor = NixiSurface,
                title = { Text("Nowa tabela", color = NixiText) },
                text = {
                    Column {
                        NixiField("Nazwa tabeli", dlgTableName) { dlgTableName = it }
                        Spacer(Modifier.height(8.dp))
                        NixiField(
                            "Kolumny, np: id bigint generated always as identity primary key, title text",
                            dlgNewCols
                        ) { dlgNewCols = it }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val t = dlgTableName.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
                        val cols = dlgNewCols.trim()
                            .ifBlank { "id bigint generated always as identity primary key" }
                        NixiState.pendingSql.value = "CREATE TABLE IF NOT EXISTS $t (\n  $cols\n);"
                        newTableDlg = false
                    }, enabled = dlgTableName.isNotBlank()) {
                        Text("Generuj SQL", color = NixiPurple)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { newTableDlg = false }) { Text("Anuluj", color = NixiTextDim) }
                },
            )
        }
        if (addColDlg) {
            AlertDialog(
                onDismissRequest = { addColDlg = false },
                containerColor = NixiSurface,
                title = { Text("Nowa kolumna", color = NixiText) },
                text = {
                    Column {
                        NixiField("Tabela", dlgTable) { dlgTable = it }
                        Spacer(Modifier.height(8.dp))
                        NixiField("kolumna typ (np. notes text)", dlgColDef) { dlgColDef = it }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val parts = dlgColDef.trim().split(" ")
                        if (parts.size >= 2 && dlgTable.isNotBlank()) {
                            NixiState.pendingSql.value =
                                "ALTER TABLE ${dlgTable.trim()} ADD COLUMN IF NOT EXISTS ${parts.joinToString(" ")};"
                            addColDlg = false
                        }
                    }, enabled = dlgTable.isNotBlank() && dlgColDef.isNotBlank()) {
                        Text("Generuj SQL", color = NixiPurple)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { addColDlg = false }) { Text("Anuluj", color = NixiTextDim) }
                },
            )
        }

        items(allTables) { name ->
            val meta = tables.firstOrNull { it.name == name }
            val cols = meta?.columns ?: emptyList<dev.nixi.db.PostgrestClient.ColumnMeta>()
            val a = SupabaseHub.accessFor(name)
            Surface(
                color = NixiSurface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (SupabaseHub.available) selected = name
                    },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            color = NixiText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        AccessChip("C", a.read)
                        AccessChip("E", a.edit)
                        AccessChip("D", a.delete)
                    }
                    if (cols.isNotEmpty()) {
                        Text(
                            cols.joinToString(", ") { it.name }.take(90),
                            color = NixiTextDim, fontSize = 11.sp, maxLines = 2,
                        )
                    } else if (meta != null) {
                        Text("kolumny: —", color = NixiTextDim, fontSize = 11.sp)
                    }
                }
            }
        }
    }

}

@Composable
private fun AccessChip(letter: String, on: Boolean) {
    Surface(
        color = if (on) NixiPurple else Color(0xFF241A44),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.padding(start = 4.dp),
    ) {
        Text(
            letter,
            color = if (on) Color.White else NixiTextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SqlProposalCard(sql: String, onDone: () -> Unit) {
    val context = LocalContext.current
    Surface(color = NixiSurfaceHi, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Nowy SQL do wklejenia (DDL)", color = NixiText,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                sql,
                color = NixiTextDim, fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .height(110.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("nixi-sql", sql))
                    onDone()
                }) {
                    Text("Skopiuj i zamknij", color = NixiPurple)
                }
                TextButton(onClick = {
                    val ref = try {
                        Uri.parse(LocalStoreUrl()).host.orEmpty().substringBefore('.')
                    } catch (_: Exception) { "" }
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
            }
        }
    }
}

private fun LocalStoreUrl(): String = dev.nixi.store.LocalStore.supabaseUrl
