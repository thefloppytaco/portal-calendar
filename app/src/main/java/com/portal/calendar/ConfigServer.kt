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
        s.uri == "/manifest.json" && s.method == Method.GET ->
            newFixedLengthResponse(Response.Status.OK, "application/json", """
                {"name":"Family Calendar","short_name":"Family",
                 "start_url":"/","display":"standalone",
                 "background_color":"#f4f1ea","theme_color":"#f0584c",
                 "icons":[{"src":"/icon.svg","sizes":"any","type":"image/svg+xml"}]}
            """.trimIndent())
        s.uri == "/icon.svg" && s.method == Method.GET ->
            newFixedLengthResponse(Response.Status.OK, "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">" +
                "<rect width=\"100\" height=\"100\" rx=\"24\" fill=\"#F0584C\"/>" +
                "<rect x=\"16\" y=\"28\" width=\"68\" height=\"56\" rx=\"10\" fill=\"white\"/>" +
                "<rect x=\"24\" y=\"42\" width=\"32\" height=\"9\" rx=\"4.5\" fill=\"#2BB3A3\"/>" +
                "<rect x=\"24\" y=\"56\" width=\"44\" height=\"9\" rx=\"4.5\" fill=\"#F2A93B\"/>" +
                "<rect x=\"24\" y=\"70\" width=\"24\" height=\"9\" rx=\"4.5\" fill=\"#4FA3E3\"/></svg>")
        s.uri == "/api/members" && s.method == Method.GET ->
            json(Members.json(ctx))
        s.uri == "/api/members" && s.method == Method.POST -> {
            Members.save(ctx, readBody(s))
            json(Members.json(ctx))
        }
        s.uri == "/api/lists" && s.method == Method.GET ->
            json(FamilyLists.json(ctx))
        s.uri == "/api/lists" && s.method == Method.POST ->
            json(FamilyLists.mutate(ctx, org.json.JSONObject(readBody(s))))
        s.uri == "/api/gtasks/link" && s.method == Method.POST -> {
            GoogleTasks.link(ctx, org.json.JSONObject(readBody(s)).getString("listId"))
            json(FamilyLists.json(ctx))
        }
        s.uri == "/api/gtasks/unlink" && s.method == Method.POST -> {
            GoogleTasks.unlink(ctx, org.json.JSONObject(readBody(s)).getString("listId"))
            json(FamilyLists.json(ctx))
        }
        s.uri == "/api/chores" && s.method == Method.GET ->
            json(Chores.statusJson(ctx))
        s.uri == "/api/chores" && s.method == Method.POST ->
            json(Chores.mutate(ctx, org.json.JSONObject(readBody(s))))
        s.uri == "/api/meals" && s.method == Method.GET ->
            json(Meals.statusJson(ctx))
        s.uri == "/api/meals" && s.method == Method.POST ->
            json(Meals.mutate(ctx, org.json.JSONObject(readBody(s))))
        s.uri == "/api/ai" && s.method == Method.GET ->
            json(Gemini.statusJson(ctx))
        s.uri == "/api/ai" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            json(Gemini.setConfig(ctx,
                if (o.has("enabled")) o.getBoolean("enabled") else null,
                if (o.has("key")) o.getString("key") else null,
                if (o.has("model")) o.getString("model") else null))
        }
        s.uri == "/api/ai/import" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            json(Gemini.smartImport(ctx,
                o.optString("text").takeIf { it.isNotEmpty() },
                o.optString("image").takeIf { it.isNotEmpty() },
                o.optString("mime").takeIf { it.isNotEmpty() }))
        }
        s.uri == "/api/ai/apply" && s.method == Method.POST ->
            json(Gemini.applyProposals(ctx, org.json.JSONObject(readBody(s))))
        s.uri == "/api/ai/recipe" && s.method == Method.POST ->
            json(Gemini.recipe(ctx, org.json.JSONObject(readBody(s)).getString("dish")))
        s.uri == "/api/ai/meal" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            json(Gemini.planMeal(ctx,
                o.getString("dish"), o.getString("date"), o.getString("slot"),
                o.optBoolean("groceries", true)))
        }
        s.uri == "/api/setup" && s.method == Method.GET ->
            json("{\"fresh\":${store.feeds().isEmpty() && !store.wizardDone()}}")
        s.uri == "/api/setup" && s.method == Method.POST -> {
            readBody(s) // drain — an unread body corrupts the next keep-alive request
            store.setWizardDone()
            json("{\"fresh\":false}")
        }
        s.uri == "/api/features" && s.method == Method.GET ->
            json(org.json.JSONObject()
                .put("chores", store.featureEnabled("chores"))
                .put("lists", store.featureEnabled("lists"))
                .put("meals", store.featureEnabled("meals")).toString())
        s.uri == "/api/features" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            for (key in listOf("chores", "lists", "meals"))
                if (o.has(key)) store.setFeature(key, o.getBoolean(key))
            App.instance.notifyDataChanged()
            json(org.json.JSONObject()
                .put("chores", store.featureEnabled("chores"))
                .put("lists", store.featureEnabled("lists"))
                .put("meals", store.featureEnabled("meals")).toString())
        }
        s.uri == "/api/pin" && s.method == Method.GET ->
            json("{\"enabled\":${store.pin().isNotEmpty()}}")
        s.uri == "/api/pin" && s.method == Method.POST -> {
            store.setPin(org.json.JSONObject(readBody(s)).optString("pin"))
            json("{\"enabled\":${store.pin().isNotEmpty()}}")
        }
        s.uri == "/api/weather" && s.method == Method.GET ->
            json(Weather.statusJson(ctx))
        s.uri == "/api/weather/search" && s.method == Method.POST ->
            json(Weather.search(org.json.JSONObject(readBody(s)).getString("q")).toString())
        s.uri == "/api/weather/set" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            Weather.set(ctx,
                if (o.has("lat")) o.getDouble("lat") else null,
                if (o.has("lon")) o.getDouble("lon") else null,
                if (o.has("label")) o.getString("label") else null,
                if (o.has("unit")) o.getString("unit") else null)
            Weather.maybeRefresh(ctx, force = true) // worker thread; fine
            json(Weather.statusJson(ctx))
        }
        s.uri == "/api/weather/clear" && s.method == Method.POST -> {
            Weather.clear(ctx)
            App.instance.notifyDataChanged()
            json(Weather.statusJson(ctx))
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
        s.uri == "/api/scale" && s.method == Method.GET ->
            json("{\"scale\":${store.uiScale()}}")
        s.uri == "/api/scale" && s.method == Method.POST -> {
            store.setUiScale(org.json.JSONObject(readBody(s)).getDouble("scale").toFloat())
            onConfigChanged() // board notices the change and rebuilds itself
            json("{\"scale\":${store.uiScale()}}")
        }
        // Live zoom while the page's slider is being dragged — GPU transform
        // only; the drag-end POST to /api/scale does the real re-layout.
        s.uri == "/api/scale/preview" && s.method == Method.POST -> {
            val v = org.json.JSONObject(readBody(s)).getDouble("scale").toFloat()
            App.instance.onMain { App.instance.activeBoard?.previewScale(v) }
            json("{\"ok\":true}")
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

    /**
     * Reads the raw body as UTF-8. NanoHTTPD's parseBody() decodes bodies
     * without an explicit charset as Latin-1, which mangles emoji and any
     * non-ASCII text (chore icons, event titles…).
     */
    private fun readBody(s: IHTTPSession): String {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) {
            val files = HashMap<String, String>()
            s.parseBody(files)
            return files["postData"] ?: ""
        }
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = s.inputStream.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun json(body: String) =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        // portal-remote already owns 8080 on this device.
        const val PORT = 8090
    }
}
