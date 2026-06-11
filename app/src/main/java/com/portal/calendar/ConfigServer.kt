package com.portal.calendar

import android.content.Context
import fi.iki.elonen.NanoHTTPD

/**
 * Tiny HTTP server so calendars are configured from a phone instead of by
 * typing on the Portal. GET / serves the setup page; the page talks JSON to
 * /api/config and /api/sync.
 */
class ConfigServer(
    private val ctx: Context,
    private val store: ConfigStore,
    private val onConfigChanged: () -> Unit
) : NanoHTTPD(PORT) {

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: IllegalArgumentException) {
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
            "{\"error\":${jsonStr(e.message ?: "invalid input")}}")
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
            "{\"error\":${jsonStr(e.message ?: e.javaClass.simpleName)}}")
    }

    private fun route(s: IHTTPSession): Response = when {
        s.uri == "/" || s.uri == "/index.html" -> {
            val html = ctx.assets.open("config.html").bufferedReader().readText()
            newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
        s.uri == "/api/config" && s.method == Method.GET ->
            json(store.feedsJson())
        s.uri == "/api/config" && s.method == Method.POST -> {
            store.saveJson(readBody(s))
            onConfigChanged()
            json("{\"ok\":true}")
        }
        s.uri == "/api/sync" && s.method == Method.POST -> {
            onConfigChanged()
            json("{\"ok\":true}")
        }
        s.uri == "/api/writers" && s.method == Method.GET ->
            json(Writers.statusJson(ctx))
        s.uri == "/api/target" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            Writers.setTarget(ctx, o.getString("kind"), o.getString("id"))
            json(Writers.statusJson(ctx))
        }
        s.uri == "/api/icloud/connect" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            CalDav.connect(ctx, o.getString("email").trim(), o.getString("password").trim())
            Writers.ensureDefault(ctx)
            json(Writers.statusJson(ctx))
        }
        s.uri == "/api/icloud/disconnect" && s.method == Method.POST -> {
            CalDav.disconnect(ctx)
            Writers.ensureDefault(ctx)
            json(Writers.statusJson(ctx))
        }
        s.uri == "/api/google/begin" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            val url = GoogleCal.begin(ctx, o.getString("clientId").trim(), o.getString("clientSecret").trim())
            json(org.json.JSONObject().put("authUrl", url).toString())
        }
        s.uri == "/api/google/code" && s.method == Method.POST -> {
            GoogleCal.finish(ctx, org.json.JSONObject(readBody(s)).getString("code"))
            Writers.ensureDefault(ctx)
            json(Writers.statusJson(ctx))
        }
        s.uri == "/api/google/disconnect" && s.method == Method.POST -> {
            GoogleCal.disconnect(ctx)
            Writers.ensureDefault(ctx)
            json(Writers.statusJson(ctx))
        }
        // Landing spot if the user swaps localhost→portal-ip on the Google
        // redirect URL: completes the connection right here.
        s.uri == "/oauth" && s.method == Method.GET -> {
            val code = s.parameters["code"]?.firstOrNull()
            val msg = try {
                if (code.isNullOrEmpty()) throw IllegalArgumentException("no code in the URL")
                GoogleCal.finish(ctx, code)
                Writers.ensureDefault(ctx)
                "✓ Google Calendar connected — you can close this tab and go back to the setup page."
            } catch (e: Exception) {
                "Couldn't finish the Google sign-in: ${e.message}"
            }
            newFixedLengthResponse(Response.Status.OK, "text/html",
                "<html><body style=\"font-family:sans-serif;background:#f4f1ea;color:#333a45;padding:40px;\"><h2>$msg</h2></body></html>")
        }
        s.uri == "/api/event" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            val title = o.getString("title").trim()
            if (title.isEmpty()) throw IllegalArgumentException("the event needs a title")
            val allDay = o.optBoolean("allDay", false)
            val (start, end) = CalDav.eventWindow(
                o.getString("date"), o.optString("time", null),
                o.optInt("durationMins", 60), allDay)
            Writers.addEvent(ctx, title, start, end, allDay)
            onConfigChanged() // pull feeds so the new event shows up ASAP
            json("{\"ok\":true}")
        }
        s.uri == "/api/validate" && s.method == Method.POST -> {
            val url = org.json.JSONObject(readBody(s)).getString("url")
            val count = App.instance.sync.validateFeed(url)
            json("{\"ok\":true,\"events\":$count}")
        }
        s.uri == "/api/screensaver" && s.method == Method.GET ->
            json(Screensaver.statusJson(ctx))
        s.uri == "/api/screensaver" && s.method == Method.POST -> {
            val enabled = org.json.JSONObject(readBody(s)).getBoolean("enabled")
            Screensaver.set(ctx, enabled)
            json(Screensaver.statusJson(ctx))
        }
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
    }

    private fun readBody(s: IHTTPSession): String {
        val files = HashMap<String, String>()
        s.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun json(body: String) =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        // portal-remote already owns 8080 on this device.
        const val PORT = 8090
    }
}
