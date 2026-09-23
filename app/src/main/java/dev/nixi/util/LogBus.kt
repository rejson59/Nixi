package dev.nixi.util

import dev.nixi.db.SupabaseHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Dwa poziomy logowania:
 *  1. bufor w pamięci (ostatnie 300 wpisów) — ekran "Logi" działa offline,
 *  2. asynchroniczne zapisy do Supabase (system_logs / errors) — tylko gdy konfigurowane.
 */
object LogBus {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deque = ConcurrentLinkedDeque<LogEntry>()
    const val MAX_ENTRIES = 300

    data class LogEntry(
        val ts: Long,
        val action: String,
        val detail: String,
        val status: String, // ok | error | warn
        val table: String,  // system_logs | errors
    )

    @Synchronized
    fun log(action: String, detail: String = "", status: String = "ok") {
        add(LogEntry(System.currentTimeMillis(), action, detail.take(500), status, "system_logs"))
    }

    @Synchronized
    fun logException(tag: String, t: Throwable) {
        val detail = buildString {
            append(t.javaClass.simpleName).append(": ").append(t.message ?: "")
            val st = RuntimeException()
            if (st.stackTrace.size > 4) append(" | at ").append(st.stackTrace[4].toString())
        }
        add(LogEntry(System.currentTimeMillis(), tag, detail.take(900), "error", "errors"))
    }

    private fun add(entry: LogEntry) {
        deque.addFirst(entry)
        while (deque.size > MAX_ENTRIES) deque.removeLast()
        // fire-and-forget: logi NIGDY nie mogą zablokować krytycznej ścieżki
        // (ani wywalić aplikacji, gdy LocalStore nie jest jeszcze gotowy)
        val configured = runCatching { SupabaseHub.isConfigured() }.getOrDefault(false)
        if (configured) {
            scope.launch {
                runCatching { SupabaseHub.insertLog(entry) }
                    .onFailure { /* cisza — logi tła nie mogą logować logów */ }
            }
        }
    }

    @Synchronized
    fun tail(n: Int = 100): List<LogEntry> = deque.take(n).toList()

    @Synchronized
    fun clear() {
        deque.clear()
    }
}
