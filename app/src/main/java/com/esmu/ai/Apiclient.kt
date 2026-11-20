package com.esmu.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    private const val MODEL = "gemini-2.5-flash"
    private const val URL_API =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    private const val SYSTEM =
        "You are ESMU, a cheerful, friendly anime-style AI companion living on the user's " +
        "phone screen. Be warm and playful, and keep answers short and clear for a small screen."

    fun resolveKey(ctx: Context): String {
        val saved = ctx.getSharedPreferences("esmu", Context.MODE_PRIVATE)
            .getString("api_key", "") ?: ""
        return if (saved.isNotBlank()) saved else BuildConfig.API_KEY
    }

    fun chat(apiKey: String, messages: JSONArray): String {
        val conn = URL(URL_API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)

            val contents = JSONArray()
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                val role = if (m.getString("role") == "assistant") "model" else "user"
                contents.put(JSONObject().put("role", role).put(
                    "parts", JSONArray().put(JSONObject().put("text", m.getString("content")))))
            }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                .put("contents", contents)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw Exception("API error $code: $text")

            val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            return sb.toString().trim()
        } finally {
            conn.disconnect()
        }
    }
}
