package com.portal.calendar

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Small, write-only Gotify client. Tokens are never returned or logged. */
object Gotify {
    private const val PREFS = "config"
    private const val URL_KEY = "gotify_url"
    private const val TOKEN_KEY = "gotify_token"
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()

    fun configured(ctx: Context): Boolean = url(ctx).isNotBlank() && token(ctx).isNotBlank()
    fun url(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(URL_KEY, "").orEmpty().trim().trimEnd('/')

    fun save(ctx: Context, endpoint: String, token: String?) {
        val clean = endpoint.trim().trimEnd('/')
        require(clean.startsWith("http://") || clean.startsWith("https://")) { "Gotify URL must use http or https" }
        val edit = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(URL_KEY, clean)
        if (!token.isNullOrBlank()) edit.putString(TOKEN_KEY, token.trim())
        edit.commit()
    }

    fun send(ctx: Context, title: String, message: String, priority: Int = 5): Boolean {
        val endpoint = url(ctx); val key = token(ctx)
        if (endpoint.isBlank() || key.isBlank()) return false
        val body = JSONObject().put("title", title).put("message", message)
            .put("priority", priority.coerceIn(0, 10)).toString()
        val request = Request.Builder().url("$endpoint/message")
            .addHeader("X-Gotify-Key", key)
            .post(body.toRequestBody("application/json".toMediaType())).build()
        return runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    private fun token(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(TOKEN_KEY, "").orEmpty()
}
