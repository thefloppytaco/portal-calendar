package com.portal.calendar

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Optional AI features via the user's own Google AI (Gemini) API key — chosen
 * because the free tier makes it accessible. Entirely opt-in: until the
 * toggle is on AND a key is saved, no AI options appear anywhere.
 *
 * Model resilience: the key is validated by listing the account's available
 * models; the picker is populated from that live list, and if the chosen
 * model ever 404s the call retries once with the best available fallback —
 * so Google renaming model generations doesn't break the feature.
 */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_T = "application/json; charset=utf-8".toMediaType()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("ai_enabled", false)
    fun isReady(ctx: Context): Boolean =
        isEnabled(ctx) && !prefs(ctx).getString("ai_key", null).isNullOrEmpty()

    fun statusJson(ctx: Context): String = JSONObject()
        .put("enabled", isEnabled(ctx))
        .put("hasKey", !prefs(ctx).getString("ai_key", null).isNullOrEmpty())
        .put("model", prefs(ctx).getString("ai_model", "") ?: "")
        .put("models", JSONArray(prefs(ctx).getString("ai_models", "[]")))
        .toString()

    /** Saves config; a new key is validated by fetching the live model list. */
    fun setConfig(ctx: Context, enabled: Boolean?, key: String?, model: String?): String {
        val e = prefs(ctx).edit()
        enabled?.let { e.putBoolean("ai_enabled", it) }
        model?.let { e.putString("ai_model", it) }
        e.apply()
        if (!key.isNullOrBlank()) {
            val models = listModels(key.trim())
            if (models.isEmpty())
                throw IllegalArgumentException("that key works, but no usable Gemini models were found on it")
            prefs(ctx).edit()
                .putString("ai_key", key.trim())
                .putString("ai_models", JSONArray(models).toString())
                .putString("ai_model",
                    prefs(ctx).getString("ai_model", "").takeIf { it in models } ?: pickDefault(models))
                .apply()
        }
        if (key != null && key.isBlank()) { // explicit clear
            prefs(ctx).edit().remove("ai_key").remove("ai_models").remove("ai_model").apply()
        }
        return statusJson(ctx)
    }

    /** Models on this key that support generateContent (names without "models/"). */
    private fun listModels(key: String): List<String> {
        val out = ArrayList<String>()
        var pageToken: String? = null
        do { // the list spans pages now — one page can miss newer models
            val url = "$BASE/models?pageSize=50&key=$key" +
                (pageToken?.let { "&pageToken=$it" } ?: "")
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 400 || resp.code == 403)
                    throw IllegalArgumentException("Google rejected that API key — copy it from aistudio.google.com → Get API key")
                if (resp.code !in 200..299)
                    throw IllegalArgumentException("couldn't reach Google AI (HTTP ${resp.code})")
                val body = JSONObject(resp.body?.string() ?: "{}")
                val models = body.optJSONArray("models") ?: JSONArray()
                for (i in 0 until models.length()) {
                    val m = models.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    val supportsGenerate = (0 until methods.length())
                        .any { methods.optString(it) == "generateContent" }
                    if (supportsGenerate) out.add(m.optString("name").removePrefix("models/"))
                }
                pageToken = body.optString("nextPageToken").ifEmpty { null }
            }
        } while (pageToken != null)
        return out
    }

    /** Prefer the newest flash-class model: cheap, fast, plenty for this job. */
    private fun pickDefault(models: List<String>): String =
        models.filter { it.contains("flash") && !it.contains("lite") }.maxOrNull()
            ?: models.filter { it.contains("flash") }.maxOrNull()
            ?: models.first()

    /** One generateContent call; retries once on a fallback model if needed. */
    fun generate(ctx: Context, prompt: String, imageB64: String?, mime: String?): String {
        val key = prefs(ctx).getString("ai_key", null)
            ?: throw IllegalArgumentException("AI isn't set up — add a Gemini key in Settings")
        val model = prefs(ctx).getString("ai_model", "").orEmpty()
            .ifEmpty { pickDefault(parseModels(ctx)) }
        return try {
            callModel(key, model, prompt, imageB64, mime)
        } catch (e: ModelGoneException) {
            val fallback = parseModels(ctx).filter { it != model }
            if (fallback.isEmpty()) throw IllegalArgumentException("the Gemini model is unavailable — re-save the key in Settings to refresh the list")
            val next = pickDefault(fallback)
            prefs(ctx).edit().putString("ai_model", next).apply()
            try {
                callModel(key, next, prompt, imageB64, mime)
            } catch (e2: ModelGoneException) {
                // Both stale — tell the user something actionable, not "ModelGoneException".
                throw IllegalArgumentException(
                    "the Gemini model list is out of date — re-save the key in Settings to refresh it")
            }
        }
    }

    private class ModelGoneException : Exception()

    private fun callModel(key: String, model: String, prompt: String,
                          imageB64: String?, mime: String?): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (!imageB64.isNullOrEmpty()) {
            parts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", mime ?: "image/jpeg")
                .put("data", imageB64)))
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("response_mime_type", "application/json"))
        val req = Request.Builder()
            .url("$BASE/models/$model:generateContent?key=$key")
            .post(body.toString().toRequestBody(JSON_T))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 404) throw ModelGoneException()
            if (resp.code !in 200..299) {
                val why = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IllegalArgumentException("Gemini error: ${why ?: "HTTP ${resp.code}"}")
            }
            val candidates = JSONObject(text).optJSONArray("candidates")
                ?: throw IllegalArgumentException("Gemini returned nothing usable")
            val sb = StringBuilder()
            val partsOut = candidates.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            for (i in 0 until partsOut.length())
                sb.append(partsOut.optJSONObject(i)?.optString("text") ?: "")
            return sb.toString()
        }
    }

    private fun parseModels(ctx: Context): List<String> {
        val arr = JSONArray(prefs(ctx).getString("ai_models", "[]"))
        return (0 until arr.length()).map { arr.optString(it) }
    }

    // ------------------------------------------------------- the features

    /** Photo/text → proposed events, list items and chores (nothing applied). */
    fun smartImport(ctx: Context, text: String?, imageB64: String?, mime: String?): String {
        val members = Members.all(ctx).joinToString(", ") { it.name }
        val today = SimpleDateFormat("yyyy-MM-dd (EEEE)", Locale.US).format(Date())
        val prompt = """
            You help run a family calendar board. Today is $today.
            Family members: $members.
            Read the provided content (text and/or an image of a flyer, schedule,
            email or list) and extract actionable items for the family.
            Respond with STRICT JSON only, exactly this shape:
            {"events":[{"title":string,"date":"YYYY-MM-DD","time":"HH:MM" or null,
              "durationMins":number,"allDay":boolean}],
             "listItems":[{"list":string,"text":string}],
             "chores":[{"title":string,"member":string or "","date":"YYYY-MM-DD"}]}
            Rules: only include things clearly present; resolve relative dates
            against today; use "Groceries" or "To-Do" for list names unless
            another list is obvious; assign a chore's member only when a family
            member is clearly meant. Use empty arrays when nothing fits.
            ${if (text.isNullOrBlank()) "" else "Content:\n$text"}
        """.trimIndent()
        val raw = generate(ctx, prompt, imageB64, mime)
        val parsed = JSONObject(raw) // throws if the model ignored JSON mode
        return JSONObject()
            .put("events", parsed.optJSONArray("events") ?: JSONArray())
            .put("listItems", parsed.optJSONArray("listItems") ?: JSONArray())
            .put("chores", parsed.optJSONArray("chores") ?: JSONArray())
            .toString()
    }

    /**
     * Spoken command (WAV audio) → transcript + a friendly reply + the same
     * {events, listItems, chores} proposal shape [applyProposals] executes.
     * One Gemini call transcribes AND interprets — "private Alexa" on the
     * family's own key. Empty arrays + a reply when it's just a question.
     */
    fun voiceCommand(ctx: Context, wavB64: String): JSONObject {
        val members = Members.all(ctx).joinToString(", ") { it.name }
        val lists = JSONArray(FamilyLists.json(ctx)).let { arr ->
            (0 until arr.length()).joinToString(", ") { arr.getJSONObject(it).optString("name") }
        }
        val now = SimpleDateFormat("yyyy-MM-dd (EEEE) HH:mm", Locale.US).format(Date())
        val prompt = """
            You are the voice assistant for a family calendar board. Now is $now.
            Family members: ${members.ifEmpty { "(none yet)" }}.
            Existing lists: ${lists.ifEmpty { "(none yet)" }}.
            The attached audio is one spoken command. Transcribe it, then extract
            any actions. Respond with STRICT JSON only, exactly this shape:
            {"transcript":string,
             "reply":string (ONE short friendly sentence confirming what you did,
                             or answering if it was just a question),
             "events":[{"title":string,"date":"YYYY-MM-DD","time":"HH:MM" or null,
               "durationMins":number,"allDay":boolean}],
             "listItems":[{"list":string,"text":string}],
             "chores":[{"title":string,"member":string or "","date":"YYYY-MM-DD"}]}
            Resolve relative dates/times ("tomorrow at 3", "Friday") against now.
            Buying/getting food or supplies → a listItem on "Groceries". Telling a
            member to do something → a chore. A scheduled thing → an event. If the
            audio is empty or unintelligible, set transcript to "" and arrays empty.
        """.trimIndent()
        val raw = generate(ctx, prompt, wavB64, "audio/wav")
        val o = JSONObject(raw)
        return JSONObject()
            .put("transcript", o.optString("transcript"))
            .put("reply", o.optString("reply"))
            .put("events", o.optJSONArray("events") ?: JSONArray())
            .put("listItems", o.optJSONArray("listItems") ?: JSONArray())
            .put("chores", o.optJSONArray("chores") ?: JSONArray())
    }

    /**
     * One inbox-calendar command → a routed directive. Smarter than the
     * rule-based parser: free phrasing, picks the best existing list, and
     * cleans up spelling in the item text. Callers fall back to
     * [MagicWords.parseLoose] when this throws or no key is set.
     */
    fun parseCommand(ctx: Context, title: String): MagicWords.Directive {
        val members = Members.all(ctx)
        val lists = JSONArray(FamilyLists.json(ctx)).let { arr ->
            (0 until arr.length()).joinToString(", ") { arr.getJSONObject(it).optString("name") }
        }
        val prompt = """
            A family sends short commands to their calendar board.
            Family members: ${members.joinToString(", ") { it.name }}.
            Existing lists: ${lists.ifEmpty { "(none yet)" }}.
            Classify this command and clean up obvious typos in the item text
            (keep the meaning, fix spelling, sentence case):
            "$title"
            Respond with STRICT JSON only:
            {"kind":"list" or "chore" or "todo",
             "list":string (the best matching existing list, or a sensible new
                            name like "Groceries"; empty unless kind is "list"),
             "text":string (the cleaned item or chore title, without the member name),
             "member":string (a family member's name if the command is for them, else "")}
            A command about buying/getting food or supplies is a "list" for Groceries.
            A command telling a member to do something is a "chore".
        """.trimIndent()
        val o = JSONObject(generate(ctx, prompt, null, null))
        val text = o.optString("text").trim().ifEmpty { title }
        return when (o.optString("kind")) {
            "chore" -> {
                val m = members.firstOrNull {
                    MagicWords.fuzzyEquals(it.name.lowercase(Locale.US),
                        o.optString("member").lowercase(Locale.US))
                }
                MagicWords.Directive("chore", text, memberId = m?.id ?: "")
            }
            "list" -> MagicWords.Directive("list", text,
                listName = o.optString("list").ifEmpty { "To-Do" })
            else -> MagicWords.Directive("todo", text)
        }
    }

    /** Executes the proposals the user confirmed on the page. */
    fun applyProposals(ctx: Context, o: JSONObject): String {
        var applied = 0
        val errors = JSONArray()
        o.optJSONArray("events")?.let { evs ->
            for (i in 0 until evs.length()) {
                val ev = evs.getJSONObject(i)
                try {
                    val time = ev.optString("time").takeIf { it.isNotEmpty() && it != "null" }
                    val allDay = ev.optBoolean("allDay", false) || time == null
                    val (s, e) = CalDav.eventWindow(ev.getString("date"), time,
                        ev.optInt("durationMins", 60), allDay)
                    Writers.addEvent(ctx, ev.getString("title"), s, e, allDay)
                    applied++
                } catch (ex: Exception) {
                    errors.put("event \"${ev.optString("title")}\": ${ex.message}")
                }
            }
        }
        o.optJSONArray("listItems")?.let { items ->
            for (i in 0 until items.length()) {
                val li = items.getJSONObject(i)
                try {
                    MagicWords.addToList(ctx,
                        li.optString("list").ifEmpty { "To-Do" }, li.getString("text"))
                    applied++
                } catch (ex: Exception) {
                    errors.put("item \"${li.optString("text")}\": ${ex.message}")
                }
            }
        }
        o.optJSONArray("chores")?.let { chs ->
            for (i in 0 until chs.length()) {
                val c = chs.getJSONObject(i)
                try {
                    val memberId = Members.all(ctx).firstOrNull {
                        MagicWords.fuzzyEquals(it.name.lowercase(Locale.US),
                            c.optString("member").lowercase(Locale.US))
                    }?.id ?: ""
                    Chores.mutate(ctx, JSONObject()
                        .put("action", "addChore")
                        .put("title", c.getString("title"))
                        .put("memberId", memberId)
                        .put("icon", "⭐")
                        .put("oneTime", true)
                        .put("date", c.optString("date").ifEmpty {
                            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        }))
                    applied++
                } catch (ex: Exception) {
                    errors.put("chore \"${c.optString("title")}\": ${ex.message}")
                }
            }
        }
        return JSONObject().put("applied", applied).put("errors", errors).toString()
    }

    /**
     * The full meal loop in one shot: dish idea → recipe in the box → linked
     * into the chosen meal slot → ingredients onto Groceries (skipping things
     * already on the list, so the family just checks off what's in the pantry).
     */
    fun planMeal(ctx: Context, dish: String, date: String, slot: String,
                 addGroceries: Boolean): String {
        if (slot !in Meals.SLOTS) throw IllegalArgumentException("unknown meal slot")
        val rec = JSONObject(recipe(ctx, dish))
        val title = rec.optString("title").ifEmpty { dish }
        val recipeId = Meals.addRecipeDirect(ctx, title,
            rec.optString("ingredients"), rec.optString("steps"))
        Meals.mutate(ctx, JSONObject()
            .put("action", "setMeal")
            .put("date", date)
            .put("slot", slot)
            .put("text", title)
            .put("recipeId", recipeId))
        // A dedicated shopping list per recipe (named after it) — the family
        // checks off what's in the pantry without cluttering the main
        // Groceries list. Replanning the same dish reuses its list.
        var added = 0
        if (addGroceries) {
            val existing = HashSet<String>()
            val lists = JSONArray(FamilyLists.json(ctx))
            for (i in 0 until lists.length()) {
                val l = lists.getJSONObject(i)
                if (!MagicWords.fuzzyEquals(l.optString("name").lowercase(Locale.US),
                        title.lowercase(Locale.US))) continue
                val items = l.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    existing.add(items.getJSONObject(j).optString("text").lowercase(Locale.US))
                }
            }
            rec.optString("ingredients").split("\n")
                .map { it.trim().trimStart('-', '•', '*', ' ') }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    if (existing.none { e -> MagicWords.fuzzyEquals(e, line.lowercase(Locale.US)) }) {
                        MagicWords.addToList(ctx, title, line)
                        added++
                    }
                }
        }
        return JSONObject().put("title", title).put("groceriesAdded", added)
            .put("listName", if (addGroceries) title else "").toString()
    }

    /** Dish name → a recipe for the recipe box (returned for review, not saved). */
    fun recipe(ctx: Context, dish: String): String {
        val prompt = """
            Write a simple family-friendly recipe for: $dish
            Respond with STRICT JSON only: {"title":string,
            "ingredients":string (one ingredient per line),
            "steps":string (numbered steps, one per line)}.
            Keep it practical — common ingredients, under 12 steps.
        """.trimIndent()
        val parsed = JSONObject(generate(ctx, prompt, null, null))
        return JSONObject()
            .put("title", parsed.optString("title", dish))
            .put("ingredients", parsed.optString("ingredients"))
            .put("steps", parsed.optString("steps"))
            .toString()
    }
}
