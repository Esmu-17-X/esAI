package com.esmu.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    private const val URL_API = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-sonnet-5-5"
    private const val SYSTEM =
        "You are ESMU, a cheerful, friendly anime-style AI companion living on the user's " +
        "phone screen. Be warm and playful, and keep answers short and clear for a small screen."

    /** Key saved in the app wins; otherwise the one baked in from the GitHub secret. */
    fun resolveKey(ctx: Context): String {
        val saved = ctx.getSharedPreferences("esmu", Context.MODE_PRIVATE)
            .getString("api_key", "") ?: ""
        return if (saved.isNotBlank()) saved else BuildConfig.API_KEY
    }

    /** Blocking call - run on a background thread. */
    fun chat(apiKey: String, messages: JSONArray): String {
        val conn = URL(URL_API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")

            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 600)
                .put("system", SYSTEM)
                .put("messages", messages)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw Exception("API error $code: $text")

            val content = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.getString("text"))
            }
            return sb.toString().trim()
        } finally {
            conn.disconnect()
        }
    }
}