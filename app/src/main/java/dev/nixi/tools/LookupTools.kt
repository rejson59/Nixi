package dev.nixi.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * NIXI sama szuka odpowiedzi (pogoda, Wikipedia, szybki fakt, liczenie)
 * — bez otwierania przeglądarki i bez nowych ekranów.
 */
object LookupTools {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun run(query: String): ToolResult {
        val q = query.trim()
        if (q.isBlank()) return ToolResult.fail("Powiedz, czego mam szukać.")
        tryMath(q)?.let { return ToolResult.ok(it) }
        val low = q.lowercase()
        return try {
            if (isWeather(low)) weather(q) else fact(q)
        } catch (t: Throwable) {
            ToolResult.fail("Nie doszłam do sieci: ${t.message}")
        }
    }

    private fun isWeather(q: String) =
        q.contains("pogod") || q.contains("weather") || q.contains("deszcz") ||
            q.contains("temperat") || q.contains("stopn")

    private fun weather(q: String): ToolResult {
        val city = q.replace(Regex("(?i)pogoda|jaka jest|jaka będzie|weather|w |na "), " ")
            .trim().ifBlank { "Katowice" }
        val url = "https://wttr.in/" + enc(city) + "?format=%l:+%c+%t+%w+%h+%C&lang=pl"
        val text = get(url) ?: return ToolResult.fail("Nie mam pogody dla „$city”.")
        return ToolResult.ok(text.trim().take(240))
    }

    private fun fact(q: String): ToolResult {
        wiki(q)?.let { return ToolResult.ok(it) }
        ddg(q)?.let { return ToolResult.ok(it) }
        return ToolResult.ok(
            "Nie znalazłam krótkiego faktu. Mogę otworzyć wyszukiwarkę narzędziem phone/web."
        )
    }

    private fun wiki(q: String): String? {
        val title = enc(q)
        for (lang in listOf("pl", "en")) {
            val url = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$title"
            val body = get(url) ?: continue
            val js = runCatching { JSONObject(body) }.getOrNull() ?: continue
            if (js.optString("type") == "disambiguation") continue
            val extract = js.optString("extract")
            if (extract.length > 40) return extract.take(700)
        }
        return null
    }

    private fun ddg(q: String): String? {
        val url = "https://api.duckduckgo.com/?q=${enc(q)}&format=json&no_html=1&skip_disambig=1"
        val body = get(url) ?: return null
        val js = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val abs = js.optString("AbstractText")
        if (abs.length > 40) return abs.take(700)
        val ans = js.optString("Answer")
        if (ans.length > 8) return ans.take(400)
        val def = js.optJSONObject("Definition")?.optString("text").orEmpty()
        if (def.length > 40) return def.take(400)
        return null
    }

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "NIXI/1.3.7")
            .header("Accept", "application/json,text/plain,*/*")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Proste „ile to 12*8”. */
    fun tryMath(raw: String): String? {
        val t = raw.lowercase()
            .replace("ile to", "")
            .replace("policz", "")
            .replace("oblicz", "")
            .replace("×", "*").replace("x", "*")
            .replace("÷", "/").replace(",", ".")
            .replace(" ", "")
        val m = Regex("""^(-?\d+(?:\.\d+)?)([+\-*/])(-?\d+(?:\.\d+)?)$""").find(t) ?: return null
        val a = m.groupValues[1].toDouble()
        val b = m.groupValues[3].toDouble()
        val r = when (m.groupValues[2]) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b == 0.0) return "Nie dzielę przez zero." else a / b
            else -> return null
        }
        val out = if (r == r.toLong().toDouble()) r.toLong().toString() else
            "%.4f".format(r).trimEnd('0').trimEnd('.')
        return "$a ${m.groupValues[2]} $b = $out"
    }
}
