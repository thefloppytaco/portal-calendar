package com.portal.calendar

import android.content.Context
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Google Calendar write access for a no-GMS device, using the user's OWN
 * OAuth "Desktop app" client (created once in their Google Cloud console —
 * see the config page / README). Desktop clients permit any localhost
 * redirect, and there's no listener on the user's browser machine, so the
 * sign-in ends on a dead http://localhost:8090/oauth?code=… page; the user
 * pastes that URL back into the config page (or swaps localhost for the
 * Portal's IP, which hits ConfigServer's /oauth route directly). We exchange
 * the code for a refresh token and call the Calendar REST API from then on.
 */
object GoogleCal {
    private const val REDIRECT = "http://localhost:8090/oauth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val API = "https://www.googleapis.com/calendar/v3"
    private const val SCOPES =
        "https://www.googleapis.com/auth/calendar.events " +
        "https://www.googleapis.com/auth/calendar.readonly " +
        "https://www.googleapis.com/auth/tasks"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun isConnected(ctx: Context): Boolean = !prefs(ctx).getString("g_refresh", null).isNullOrEmpty()
    fun email(ctx: Context): String? = prefs(ctx).getString("g_email", null)

    /** id → display name pairs of writable calendars (cached at connect time). */
    fun calendars(ctx: Context): List<Pair<String, String>> {
        val arr = JSONArray(prefs(ctx).getString("g_cals", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("id") to o.getString("name")
        }
    }

    fun disconnect(ctx: Context) {
        prefs(ctx).edit()
            .remove("g_client_id").remove("g_client_secret")
            .remove("g_refresh").remove("g_access").remove("g_token_exp")
            .remove("g_cals").remove("g_email")
            .apply()
    }

    /** Stores the OAuth client and returns the URL the user signs in at. */
    fun begin(ctx: Context, clientId: String, clientSecret: String): String {
        if (clientId.isEmpty() || clientSecret.isEmpty())
            throw IllegalArgumentException("enter both the Client ID and the Client secret")
        prefs(ctx).edit()
            .putString("g_client_id", clientId)
            .putString("g_client_secret", clientSecret)
            .apply()
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=" + enc(clientId) +
            "&redirect_uri=" + enc(REDIRECT) +
            "&response_type=code&access_type=offline&prompt=consent" +
            "&scope=" + enc(SCOPES)
    }

    /** Accepts the auth code, or the whole localhost URL the browser landed on. */
    fun finish(ctx: Context, codeOrUrl: String): List<Pair<String, String>> {
        val code = Regex("[?&]code=([^&\\s]+)").find(codeOrUrl)?.groupValues?.get(1)
            ?: codeOrUrl.trim().takeIf { it.isNotEmpty() && !it.contains("://") }
            ?: throw IllegalArgumentException("couldn't find a code in that — paste the full localhost URL or just the code value")
        val p = prefs(ctx)
        val clientId = p.getString("g_client_id", null)
            ?: throw IllegalArgumentException("enter the Client ID/secret and get a sign-in link first")
        val clientSecret = p.getString("g_client_secret", "")!!

        val resp = postForm(TOKEN_URL, FormBody.Builder()
            .add("code", java.net.URLDecoder.decode(code, "UTF-8"))
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", REDIRECT)
            .add("grant_type", "authorization_code")
            .build())
        val refresh = resp.optString("refresh_token", "")
        if (refresh.isEmpty())
            throw IllegalArgumentException("Google didn't return a refresh token — remove this app at myaccount.google.com/permissions and sign in again")
        p.edit()
            .putString("g_refresh", refresh)
            .putString("g_access", resp.getString("access_token"))
            .putLong("g_token_exp", System.currentTimeMillis() + resp.optLong("expires_in", 3600) * 1000)
            .apply()

        val cals = fetchCalendars(ctx)
        if (cals.isEmpty())
            throw IllegalArgumentException("signed in, but no writable Google calendars were found")
        val arr = JSONArray()
        cals.forEach { arr.put(JSONObject().put("id", it.first).put("name", it.second)) }
        p.edit().putString("g_cals", arr.toString()).apply()
        return cals
    }

    /**
     * Deletes the event with this iCal UID from whichever writable calendar
     * holds it (matched via events.list?iCalUID). Returns true if removed.
     * For a repeating event this targets the series master → all occurrences.
     */
    fun deleteByUid(ctx: Context, uid: String): Boolean {
        if (!isConnected(ctx) || uid.isBlank()) return false
        val token = accessToken(ctx)
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        for ((calId, _) in calendars(ctx)) {
            val listReq = Request.Builder()
                .url("$API/calendars/${enc(calId)}/events?iCalUID=${enc(uid)}&showDeleted=false&maxResults=10")
                .header("Authorization", "Bearer $token").get().build()
            val items = client.newCall(listReq).execute().use { r ->
                if (r.code !in 200..299) return@use null
                JSONObject(r.body?.string() ?: "{}").optJSONArray("items")
            } ?: continue
            for (i in 0 until items.length()) {
                val id = items.getJSONObject(i).optString("id")
                if (id.isEmpty()) continue
                val delReq = Request.Builder()
                    .url("$API/calendars/${enc(calId)}/events/${enc(id)}")
                    .header("Authorization", "Bearer $token").delete().build()
                val ok = client.newCall(delReq).execute().use {
                    it.code in 200..299 || it.code == 404 || it.code == 410 // already gone = success
                }
                if (ok) return true
            }
        }
        return false
    }

    fun addEvent(ctx: Context, calendarId: String, title: String,
                 startMillis: Long, endMillis: Long, allDay: Boolean) {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun side(millis: Long) = JSONObject().apply {
            if (allDay) put("date", day.format(Date(millis)))
            else put("dateTime", utc.format(Date(millis)))
        }
        val body = JSONObject()
            .put("summary", title)
            .put("start", side(startMillis))
            .put("end", side(endMillis))
        val req = Request.Builder()
            .url("$API/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events")
            .header("Authorization", "Bearer " + accessToken(ctx))
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299)
                throw IllegalArgumentException("Google refused the event (HTTP ${resp.code}) — try reconnecting Google")
        }
    }

    /** A fresh access token for sibling Google APIs (Tasks). */
    fun bearer(ctx: Context): String = accessToken(ctx)

    // ------------------------------------------------------------ plumbing

    private fun accessToken(ctx: Context): String {
        val p = prefs(ctx)
        val cached = p.getString("g_access", null)
        if (cached != null && System.currentTimeMillis() < p.getLong("g_token_exp", 0) - 60_000)
            return cached
        val refresh = p.getString("g_refresh", null)
            ?: throw IllegalArgumentException("Google isn't connected yet")
        val resp = postForm(TOKEN_URL, FormBody.Builder()
            .add("client_id", p.getString("g_client_id", "")!!)
            .add("client_secret", p.getString("g_client_secret", "")!!)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build())
        val token = resp.getString("access_token")
        val edit = p.edit()
            .putString("g_access", token)
            .putLong("g_token_exp", System.currentTimeMillis() + resp.optLong("expires_in", 3600) * 1000)
        // If Google ever rotates the refresh token, persist it BEFORE using
        // anything — a rotated-but-unpersisted token bricks the connection.
        resp.optString("refresh_token").takeIf { it.isNotEmpty() }
            ?.let { edit.putString("g_refresh", it) }
        edit.commit() // synchronous: a crash right after must not lose this
        return token
    }

    private fun fetchCalendars(ctx: Context): List<Pair<String, String>> {
        val req = Request.Builder()
            .url("$API/users/me/calendarList?minAccessRole=writer&maxResults=100")
            .header("Authorization", "Bearer " + accessToken(ctx))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299)
                throw IllegalArgumentException("couldn't list Google calendars (HTTP ${resp.code})")
            val items = JSONObject(resp.body?.string() ?: "{}").optJSONArray("items") ?: JSONArray()
            val out = ArrayList<Pair<String, String>>()
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                if (o.optBoolean("primary", false))
                    prefs(ctx).edit().putString("g_email", o.getString("id")).apply()
                out.add(o.getString("id") to
                    (o.optString("summaryOverride").takeIf { it.isNotEmpty() }
                        ?: o.optString("summary", o.getString("id"))))
            }
            return out
        }
    }

    private fun postForm(url: String, form: FormBody): JSONObject {
        val req = Request.Builder().url(url).post(form).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            val o = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (resp.code !in 200..299) {
                val why = o.optString("error_description", o.optString("error", "HTTP ${resp.code}"))
                throw IllegalArgumentException("Google sign-in failed: $why")
            }
            return o
        }
    }
}
