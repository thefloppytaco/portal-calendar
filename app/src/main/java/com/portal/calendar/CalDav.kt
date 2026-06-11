package com.portal.calendar

import android.content.Context
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Minimal iCloud CalDAV client — just enough to discover the account's
 * calendars and PUT new events, authenticated with an Apple ID + app-specific
 * password (account.apple.com → Sign-In and Security → App-Specific Passwords).
 * Events created here land directly in iCloud, so they back-sync natively to
 * every family device; the Portal then sees them via the published ICS feed.
 *
 * Google calendars can't be written this way (their CalDAV requires OAuth),
 * which is why two-way sync is iCloud-only.
 */
object CalDav {
    data class CalInfo(val name: String, val href: String)

    private const val ROOT = "https://caldav.icloud.com/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val XML = "application/xml; charset=utf-8".toMediaType()
    private val ICS = "text/calendar; charset=utf-8".toMediaType()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun isConnected(ctx: Context): Boolean = !prefs(ctx).getString("icloud_email", null).isNullOrEmpty()
    fun email(ctx: Context): String? = prefs(ctx).getString("icloud_email", null)

    fun calendars(ctx: Context): List<CalInfo> {
        val arr = JSONArray(prefs(ctx).getString("icloud_cals", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CalInfo(o.getString("name"), o.getString("href"))
        }
    }

    fun disconnect(ctx: Context) {
        prefs(ctx).edit()
            .remove("icloud_email").remove("icloud_password")
            .remove("icloud_cals").remove("icloud_target")
            .apply()
    }

    /** Signs in, discovers calendars, persists everything. Returns the calendars. */
    fun connect(ctx: Context, email: String, password: String): List<CalInfo> {
        val principal = propfindHref(ROOT, email, password, 0,
            """<d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>""",
            "current-user-principal")
            ?: throw IllegalArgumentException("iCloud sign-in failed — check the Apple ID, and use an app-specific password (not the normal account password)")
        val principalUrl = resolve(ROOT, principal)
        val home = propfindHref(principalUrl, email, password, 0,
            """<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>""",
            "calendar-home-set")
            ?: throw IllegalArgumentException("signed in, but couldn't locate the calendar home")
        val cals = listCalendars(resolve(principalUrl, home), email, password)
        if (cals.isEmpty())
            throw IllegalArgumentException("signed in, but found no writable calendars on this account")
        val arr = JSONArray()
        cals.forEach { arr.put(JSONObject().put("name", it.name).put("href", it.href)) }
        prefs(ctx).edit()
            .putString("icloud_email", email)
            .putString("icloud_password", password)
            .putString("icloud_cals", arr.toString())
            .apply()
        return cals
    }

    /** Creates a VEVENT in the given calendar collection. */
    fun addEventTo(ctx: Context, target: String, title: String,
                   startMillis: Long, endMillis: Long, allDay: Boolean) {
        val email = email(ctx) ?: throw IllegalArgumentException("iCloud isn't connected yet")
        val password = prefs(ctx).getString("icloud_password", null)
            ?: throw IllegalArgumentException("iCloud isn't connected yet")

        val uid = UUID.randomUUID().toString().uppercase(Locale.US)
        val utc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val dateOnly = SimpleDateFormat("yyyyMMdd", Locale.US)
        val body = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Portal Family Calendar//EN\r\n")
            append("BEGIN:VEVENT\r\nUID:").append(uid).append("\r\n")
            append("DTSTAMP:").append(utc.format(Date())).append("\r\n")
            append("SUMMARY:").append(escape(title)).append("\r\n")
            if (allDay) {
                append("DTSTART;VALUE=DATE:").append(dateOnly.format(Date(startMillis))).append("\r\n")
                append("DTEND;VALUE=DATE:").append(dateOnly.format(Date(endMillis))).append("\r\n")
            } else {
                append("DTSTART:").append(utc.format(Date(startMillis))).append("\r\n")
                append("DTEND:").append(utc.format(Date(endMillis))).append("\r\n")
            }
            append("END:VEVENT\r\nEND:VCALENDAR\r\n")
        }
        val url = resolve(ROOT, target).trimEnd('/') + "/" + uid + ".ics"
        val req = Request.Builder().url(url)
            .header("Authorization", Credentials.basic(email, password))
            .header("If-None-Match", "*")
            .put(body.toRequestBody(ICS))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 401)
                throw IllegalArgumentException("iCloud rejected the sign-in — reconnect with a fresh app-specific password")
            if (resp.code !in 200..299)
                throw IllegalArgumentException("iCloud refused the event (HTTP ${resp.code})")
        }
    }

    // ------------------------------------------------------------ plumbing

    private fun dav(url: String, email: String, password: String, depth: Int, body: String): String {
        val req = Request.Builder().url(url)
            .header("Authorization", Credentials.basic(email, password))
            .header("Depth", depth.toString())
            .method("PROPFIND", body.toRequestBody(XML))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 401)
                throw IllegalArgumentException("iCloud sign-in failed (401) — use an app-specific password from account.apple.com, not the normal password")
            if (resp.code !in 200..299 && resp.code != 207)
                throw IllegalArgumentException("iCloud error HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    /** First <elem>…<href>VALUE</href> in the response, namespace-prefix agnostic. */
    private fun propfindHref(url: String, email: String, password: String, depth: Int,
                             body: String, elem: String): String? {
        val xml = dav(url, email, password, depth, body)
        val m = Regex("<[^>]*$elem[^>]*>\\s*<[^>]*href[^>]*>([^<]+)</",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(xml)
        return m?.groupValues?.get(1)?.trim()
    }

    private fun listCalendars(homeUrl: String, email: String, password: String): List<CalInfo> {
        val xml = dav(homeUrl, email, password, 1,
            """<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
               <d:prop><d:displayname/><d:resourcetype/><c:supported-calendar-component-set/></d:prop>
               </d:propfind>""")
        android.util.Log.i("PortalCalDAV", "calendar-home response (${xml.length} chars): " + xml.take(6000))
        val out = ArrayList<CalInfo>()
        for (m in Regex("<(?:\\w+:)?response[ >](.*?)</(?:\\w+:)?response>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(xml)) {
            val block = m.groupValues[1]
            // A real event calendar: resourcetype contains a CalDAV <calendar>
            // element — which iCloud writes with an inline xmlns attribute, so
            // accept anything after the element name. Excludes inbox/outbox/
            // notifications (different element names) and Reminders lists
            // (VTODO-only component set).
            if (!Regex("<(?:\\w+:)?calendar[\\s/>]", RegexOption.IGNORE_CASE).containsMatchIn(block)) continue
            if (block.contains("VTODO", ignoreCase = true) &&
                !block.contains("VEVENT", ignoreCase = true)) continue
            val href = Regex("<(?:\\w+:)?href[^>]*>([^<]+)</", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.trim() ?: continue
            val name = Regex("<(?:\\w+:)?displayname[^>]*>([^<]*)</", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.trim()
                .takeUnless { it.isNullOrEmpty() } ?: continue
            out.add(CalInfo(unescapeXml(name), resolve(homeUrl, href)))
        }
        return out
    }

    private fun resolve(base: String, href: String): String =
        if (href.startsWith("http")) href else URI(base).resolve(href).toString()

    private fun unescapeXml(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    private fun escape(s: String): String = s
        .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    /** Builds start/end millis from the config page's form fields (device tz). */
    fun eventWindow(date: String, time: String?, durationMins: Int, allDay: Boolean): Pair<Long, Long> {
        val parts = date.split("-").map { it.toInt() }
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(parts[0], parts[1] - 1, parts[2])
        return if (allDay) {
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            start to cal.timeInMillis
        } else {
            val t = (time ?: "12:00").split(":").map { it.toInt() }
            cal.set(Calendar.HOUR_OF_DAY, t[0])
            cal.set(Calendar.MINUTE, t[1])
            val start = cal.timeInMillis
            start to start + durationMins * 60_000L
        }
    }
}
