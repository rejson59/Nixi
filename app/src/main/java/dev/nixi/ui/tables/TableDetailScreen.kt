package dev.nixi.ui.tables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import dev.nixi.db.DiscoveredTables
import dev.nixi.db.SupabaseHub
import dev.nixi.ui.onboarding.NixiField
import dev.nixi.ui.theme.NixiBg
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiSurface
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.ui.theme.NixiWarn
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Przeglądarka wierszy jednej tabeli: CRUD + dostęp NIXI. */
@Composable
fun TableDetailScreen(table: String, onBack: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val meta = remember(table) {
        DiscoveredTables.value.value.firstOrNull { it.name == table }
    }
    var rows by remember(table) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var columns by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var offset by remember { mutableStateOf(0) }
    var editRow by remember { mutableStateOf<JSONObject?>(null) }
    var newOpen by remember { mutableStateOf(false) }
    var deleteRow by remember { mutableStateOf<JSONObject?>(null) }
    var newIdValue by remember { mutableStateOf("") }
    var newValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val a = SupabaseHub.accessFor(table)
    val canEdit = a.edit && SupabaseHub.available
    val canDelete = a.delete && SupabaseHub.available

    fun load() {
        loading = true
        error = ""
        scope.launch {
            val r = runCatching {
                SupabaseHub.c().listRows(table, limit = 25, offset = offset)
            }.getOrElse {
                error = it.message ?: "błąd"
                loading = false
                return@launch
            }
            if (!r.ok) {
                error = r.error ?: "błąd"
            } else {
                rows = r.rows
                if (columns.isEmpty() && r.rows.isNotEmpty()) {
                    columns = r.rows.first().keys().asSequence().toList()
                }
            }
            loading = false
        }
    }
    load()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wróć", tint = NixiText)
                }
                Text(
                    table,
                    color = NixiText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { load() }) { Text("Odśwież", color = NixiPurple, fontSize = 12.sp) }
                if (canEdit) {
                    OutlinedButton(onClick = {
                        newIdValue = ""
                        newValues = emptyMap()
                        newOpen = true
                    }) {
                        Icon(Icons.Filled.Add, null, tint = NixiPurple)
                    }
                }
            }
        }

        item {
            // dostęp NIXI
            Surface(color = NixiSurface, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Dostęp NIXI:", color = NixiTextDim, fontSize = 12.sp)
                    ToggleSwitch("czyta", a.read) {
                        scope.launch { SupabaseHub.setAccess(table, it, a.edit, a.delete) }
                    }
                    ToggleSwitch("edytuje", a.edit) {
                        scope.launch { SupabaseHub.setAccess(table, a.read, it, a.delete) }
                    }
                    ToggleSwitch("usuwa", a.delete) {
                        scope.launch { SupabaseHub.setAccess(table, a.read, a.edit, it) }
                    }
                }
            }
        }

        if (loading) {
            item { Text("Ładowanie…", color = NixiTextDim, fontSize = 13.sp) }
        }
        if (error.isNotBlank()) {
            item { Text(error, color = NixiWarn, fontSize = 12.sp) }
        }
        if (!loading && rows.isEmpty() && error.isBlank()) {
            item { Text("Brak wierszy w tej tabeli.", color = NixiTextDim, fontSize = 13.sp) }
        }

        items(rows) { row ->
            Surface(
                color = NixiSurface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editRow = row },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val idVal = row.optString("id", "")
                        Text(
                            idVal.ifBlank { "—" },
                            color = NixiPurple,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            preview(row, columns),
                            color = NixiText,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (canEdit) {
                            IconButton(onClick = { editRow = row }) {
                                Icon(Icons.Filled.Edit, "Edytuj", tint = NixiTextDim)
                            }
                        }
                        if (canDelete && idVal.isNotBlank()) {
                            IconButton(onClick = { deleteRow = row }) {
                                Icon(Icons.Filled.Delete, "Usuń", tint = NixiWarn)
                            }
                        }
                    }
                }
            }
        }

        if (rows.size >= 25) {
            item {
                Button(
                    onClick = {
                        offset += 25
                        load()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NixiPurple),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Więcej wierszy") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    // ── dialog edycji ───────────────────────────────────────
    editRow?.let { row ->
        var values by remember(row) { mutableStateOf(initialValues(row, columns)) }
        val idVal = row.optString("id", "")
        AlertDialog(
            onDismissRequest = { editRow = null },
            containerColor = NixiSurface,
            title = {
                Text("Wiersz ${idVal.ifBlank { "(bez id)" }} — $table", color = NixiText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    columns.filter { it != "id" }.forEach { col ->
                        NixiField(col, values[col] ?: "") { values = values + (col to it) }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (columns.isEmpty()) {
                        Text("Brak danych do edycji (tabela pusta?).", color = NixiTextDim,
                            fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (idVal.isNotBlank() && canEdit) {
                        val json = JSONObject()
                        values.forEach { (k, v) -> json.put(k, parseValue(v)) }
                        scope.launch {
                            SupabaseHub.updateRow(table, mapOf("id" to "eq.$idVal"), json)
                            editRow = null
                            load()
                        }
                    }
                }, enabled = canEdit && idVal.isNotBlank()) {
                    Text("Zapisz", color = NixiPurple)
                }
            },
            dismissButton = {
                TextButton(onClick = { editRow = null }) { Text("Anuluj", color = NixiTextDim) }
            },
        )
    }

    // ── dialog nowego wiersza ───────────────────────────────
    if (newOpen) {
        AlertDialog(
            onDismissRequest = { newOpen = false },
            containerColor = NixiSurface,
            title = { Text("Nowy wiersz — $table", color = NixiText) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NixiField("id (pozostaw puste, jeśli auto-increment)", newIdValue) { newIdValue = it }
                    Spacer(Modifier.height(8.dp))
                    columns.filter { it != "id" }.forEach { col ->
                        NixiField(col, newValues[col] ?: "") {
                            newValues = newValues + (col to it)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (canEdit) {
                        val json = JSONObject()
                        if (newIdValue.isNotBlank()) json.put("id", parseValue(newIdValue))
                        newValues.forEach { (k, v) -> if (v.isNotBlank()) json.put(k, parseValue(v)) }
                        scope.launch {
                            SupabaseHub.insertRow(table, json)
                            newOpen = false
                            offset = 0
                            load()
                        }
                    }
                }, enabled = canEdit) {
                    Text("Dodaj", color = NixiPurple)
                }
            },
            dismissButton = {
                TextButton(onClick = { newOpen = false }) { Text("Anuluj", color = NixiTextDim) }
            },
        )
    }

    // ── potwierdzenie usunięcia ─────────────────────────────
    deleteRow?.let { row ->
        val idVal = row.optString("id", "")
        AlertDialog(
            onDismissRequest = { deleteRow = null },
            containerColor = NixiSurface,
            title = { Text("Usunąć wiersz?", color = NixiWarn) },
            text = {
                Text(
                    "Tabela $table, id: $idVal.\n${preview(row, columns).take(200)}",
                    color = NixiTextDim
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (canDelete && idVal.isNotBlank()) {
                        scope.launch {
                            SupabaseHub.deleteRows(table, mapOf("id" to "eq.$idVal"))
                            deleteRow = null
                            load()
                        }
                    }
                }, enabled = canDelete) {
                    Text("Tak, usuń", color = NixiWarn)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteRow = null }) { Text("Nie", color = NixiTextDim) }
            },
        )
    }
}

@Composable
private fun ToggleSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = NixiTextDim, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

private fun preview(row: JSONObject, columns: List<String>): String {
    val keys = if (columns.isNotEmpty()) columns.filter { it != "id" } else row.keys().asSequence().toList()
    return keys.take(3).joinToString(" • ") {
        val v = row.optString(it)
        "$it=${v.take(40)}"
    }
}

private fun initialValues(row: JSONObject, columns: List<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    val keys = if (columns.isNotEmpty()) columns.filter { it != "id" } else row.keys().asSequence().toList()
    for (k in keys) map[k] = row.opt(k).toString()
    return map
}

/** Parsuje wartość z pola: null/true/false/liczba/JSON albo string. */
internal fun parseValue(raw: String): Any {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    if (t.equals("null", true)) return JSONObject.NULL
    if (t == "true" || t == "false") return t == "true"
    return try {
        if (t.startsWith("{") || t.startsWith("[")) {
            org.json.JSON.parse(t)
        } else if (t.toLongOrNull() != null || t.toDoubleOrNull() != null) {
            org.json.JSON.parse(t)
        } else {
            t
        }
    } catch (_: Exception) {
        t
    }
}
