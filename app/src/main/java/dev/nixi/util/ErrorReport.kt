package dev.nixi.util

import dev.nixi.store.LocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Krótka taśma błędów — to, co użytkownik może skopiować i wkleić agentowi.
 * Nie trzyma ostrzeżeń ani szumu (gest przerwany, cisza mikrofonu).
 */
object ErrorReport {

    data class Item(val ts: Long, val where: String, val what: String)

    private val flow = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = flow

    private val fmt = SimpleDateFormat("dd.MM HH:mm", Locale("pl"))
    private const val MAX = 8

    fun load() {
        val raw = runCatching { LocalStore.errorTape }.getOrDefault("")
        if (raw.isBlank()) return
        val list = ArrayList<Item>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Item(o.optLong("ts"), o.optString("where"), o.optString("what")))
            }
        }
        flow.value = list
    }

    fun note(where: String, what: String) {
        val w = where.trim().take(40)
        val t = what.trim().replace(Regex("\\s+"), " ").take(180)
        if (w.isBlank() || t.isBlank()) return
        if (isNoise(w, t)) return
        val now = System.currentTimeMillis()
        val cur = flow.value
        val last = cur.firstOrNull()
        if (last != null && last.where == w && last.what == t && now - last.ts < 20_000) return
        val next = (listOf(Item(now, w, t)) + cur).take(MAX)
        flow.value = next
        persist(next)
    }

    fun snapshot(): String = buildString {
        append("NIXI ").append(dev.nixi.BuildConfig.VERSION_NAME)
        append(" (").append(dev.nixi.BuildConfig.VERSION_CODE).append(")\n")
        val reason = runCatching { LocalStore.lastSessionReason }.getOrDefault("")
        if (reason.isNotBlank()) {
            append("ostatnia sesja: ").append(reason)
            append(" · setup=").append(LocalStore.lastSessionSetupOk)
            append(" · audio ").append(LocalStore.lastSessionAudioChunks)
            append("/").append(LocalStore.lastSessionAudioReplies)
            append('\n')
        }
        val list = flow.value
        if (list.isEmpty()) append("brak zapisanych błędów\n")
        else list.take(5).forEach {
            append(fmt.format(Date(it.ts))).append(" [").append(it.where).append("] ")
            append(it.what).append('\n')
        }
    }

    fun headline(): String {
        val i = flow.value.firstOrNull() ?: return ""
        return "${i.where}: ${i.what}"
    }

    fun clear() {
        flow.value = emptyList()
        persist(emptyList())
    }

    private fun persist(list: List<Item>) {
        val arr = JSONArray()
        list.forEach { i ->
            arr.put(JSONObject().put("ts", i.ts).put("where", i.where).put("what", i.what))
        }
        runCatching { LocalStore.errorTape = arr.toString() }
    }

    private fun isNoise(where: String, what: String): Boolean {
        val w = where.lowercase()
        val t = what.lowercase()
        if (w.startsWith("accessibility") && t.contains("przerw")) return true
        if (w == "live.gate") return true
        if (w == "db.check") return true
        if (t.contains("authorization_pending")) return true
        return false
    }
}
