package dev.nixi.tools

import android.app.Application
import org.json.JSONObject

/**
 * Wynik narzędzia — tekst wraca do modelu (zwięzły, do wypowiedzenia).
 * [imageB64] — opcjonalnie JPEG (zrzut ekranu), który sesja wyśle
 * do Live API jako klatkę wideo.
 */
data class ToolResult(
    val ok: Boolean,
    val text: String,
    val imageB64: String? = null,
) {
    companion object {
        fun ok(text: String, imageB64: String? = null) = ToolResult(true, text, imageB64)
        fun fail(text: String) = ToolResult(false, text)
    }
}

/** Wspólny kontekst dla implementacji narzędzi. */
object ToolContext {
    lateinit var app: Application
    var screenWidthPx = 0
    var screenHeightPx = 0
    var lastToolArgs = JSONObject()
}
