package dev.nixi.db

import android.content.Context
import dev.nixi.util.LogBus
import org.json.JSONObject
import java.io.File

/**
 * Trwała kolejka zapisów, które nie doszły do Supabase (brak sieci, 5xx,
 * limit). Bez niej fakty z pamięci długotrwałej i logi przepadały bez śladu:
 * zapisy szły „fire and forget”, a błąd sieci nie był nigdzie odnotowywany.
 *
 * Plik: filesDir/pending_uploads.jsonl — jedna operacja na linię.
 * Kolejka jest przycinana (liczba wpisów i rozmiar), żeby nie rosła w
 * nieskończoność, gdy telefon długo jest offline.
 */
object OfflineQueue {

    private const val FILE_NAME = "pending_uploads.jsonl"
    private const val MAX_ENTRIES = 300
    private const val MAX_BYTES = 512 * 1024L

    const val TYPE_FACT = "fact"
    const val TYPE_CONVERSATION = "conversation"
    const val TYPE_LOG = "log"

    private val lock = Any()

    /** Trwa flush — LogBus nie może wtedy dokładać do kolejki (pętla). */
    @Volatile var isFlushing = false
        private set

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun enqueue(context: Context, type: String, payload: JSONObject) {
        synchronized(lock) {
            try {
                val f = file(context.applicationContext)
                trimIfNeeded(f)
                val entry = JSONObject()
                    .put("t", type)
                    .put("ts", System.currentTimeMillis())
                    .put("p", payload)
                f.appendText(entry.toString() + "\n")
            } catch (t: Throwable) {
                LogBus.log("queue.add", t.message ?: "nie mogę zapisać kolejki", "warn")
            }
        }
    }

    fun size(context: Context): Int = synchronized(lock) {
        runCatching { readLines(file(context.applicationContext)).size }.getOrDefault(0)
    }

    /** Próba doręczenia wszystkiego, co czeka. Bezpieczne do wołania często. */
    suspend fun flush(context: Context) {
        if (!SupabaseHub.available) return
        if (isFlushing) return
        val app = context.applicationContext
        val pending = synchronized(lock) {
            runCatching { readLines(file(app)) }.getOrDefault(emptyList())
        }
        if (pending.isEmpty()) return
        isFlushing = true
        try {
            val failed = ArrayList<String>(pending.size)
            var sent = 0
            for (line in pending) {
                val ok = runCatching { send(line) }.getOrDefault(false)
                if (ok) sent++ else failed.add(line)
            }
            synchronized(lock) {
                val f = file(app)
                if (failed.isEmpty()) {
                    f.delete()
                } else {
                    f.writeText(failed.joinToString(separator = "") { it + "\n" })
                }
            }
            if (sent > 0) {
                LogBus.log(
                    "queue.flush",
                    "dosłano $sent zaległych zapisów" +
                        if (failed.isEmpty()) "" else " (zostało ${failed.size})"
                )
            }
        } finally {
            isFlushing = false
        }
    }

    private suspend fun send(line: String): Boolean {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return true // uszkodzony wiersz - wyrzucamy
        val type = o.optString("t")
        val p = o.optJSONObject("p") ?: return true
        return when (type) {
            TYPE_FACT -> SupabaseHub.upsertFact(
                p.optString("key"), p.optString("value"), p.optString("category", "ogólne")
            )

            TYPE_CONVERSATION -> SupabaseHub.c().insert(Tables.RECENT, p).ok

            TYPE_LOG -> {
                val row = p.optJSONObject("row") ?: return true
                val table = p.optString("table", Tables.LOGS)
                SupabaseHub.c().insert(table, row).ok
            }

            else -> true // nieznany typ (starsza wersja) - nie blokujemy kolejki
        }
    }

    private fun readLines(f: File): List<String> {
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }
    }

    /** Chroni plik przed nieograniczonym wzrostem: zostawiamy najnowsze wpisy. */
    private fun trimIfNeeded(f: File) {
        if (!f.exists()) return
        if (f.length() <= MAX_BYTES) return
        val kept = readLines(f).takeLast(MAX_ENTRIES)
        f.writeText(kept.joinToString(separator = "") { it + "\n" })
    }
}
