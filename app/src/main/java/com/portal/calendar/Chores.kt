package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Chores & star rewards. A chore belongs to a member and repeats on chosen
 * weekdays (1=Sun … 7=Sat, matching java.util.Calendar). Completions are an
 * append-only log of {choreId, date, memberId}; toggling today removes/adds
 * the entry. Stars = completions this week, compared against a per-member
 * weekly goal. memberId is stamped at completion time so a star survives its
 * chore being pruned or deleted (older logs without it fall back to looking
 * the chore up).
 */
object Chores {
    private const val FILE = "chores.json"
    private const val DONE = "chore_done.json"
    private const val GOALS = "star_goals.json"
    private const val HISTORY = "chore_history.json"
    const val DEFAULT_GOAL = 10

    private fun dayFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** [includePins] only for the board's own PIN pad — never over HTTP. */
    fun statusJson(ctx: Context, includePins: Boolean = false): String {
        var chores = Data.readArray(ctx, FILE)
        // One-time chores quietly retire a few days after their date.
        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -3) }
        val cut = dayFmt().format(cutoff.time)
        val needsPrune = (0 until chores.length()).any {
            val c = chores.getJSONObject(it)
            c.optBoolean("oneTime") && c.optString("date") < cut
        }
        if (needsPrune) chores = Data.mutate(ctx, FILE) { arr ->
            for (i in arr.length() - 1 downTo 0) {
                val c = arr.getJSONObject(i)
                if (c.optBoolean("oneTime") && c.optString("date") < cut) arr.remove(i)
            }
            arr
        }
        val done = Data.readArray(ctx, DONE)
        val today = dayFmt().format(Date())

        val doneToday = JSONArray()
        for (i in 0 until done.length()) {
            val d = done.getJSONObject(i)
            if (d.optString("date") == today) doneToday.put(d.optString("choreId"))
        }

        val choreMember = HashMap<String, String>()
        for (i in 0 until chores.length()) {
            val c = chores.getJSONObject(i)
            choreMember[c.optString("id")] = c.optString("memberId")
        }
        val weekStart = weekStartStr(ctx)
        val stars = JSONObject()
        for (i in 0 until done.length()) {
            val d = done.getJSONObject(i)
            if (d.optString("date") >= weekStart) {
                // Stamped memberId first (survives chore prune/delete);
                // fall back to the chore lookup for pre-stamp log entries.
                val m = d.optString("memberId")
                    .ifEmpty { choreMember[d.optString("choreId")] ?: "" }
                if (m.isNotEmpty()) stars.put(m, stars.optInt(m, 0) + 1)
            }
        }

        val goals = JSONObject()
        val g = Data.readArray(ctx, GOALS)
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            goals.put(o.optString("memberId"), o.optInt("goal", DEFAULT_GOAL))
        }

        return JSONObject()
            .put("chores", chores)
            .put("doneToday", doneToday)
            .put("stars", stars)
            .put("goals", goals)
            .put("members", JSONArray(
                if (includePins) Members.json(ctx) else Members.publicJson(ctx)))
            .put("suggestions", suggestions(ctx, chores))
            .toString()
    }

    /** The family's frequently re-added chores, fuzzy-grouped, active ones excluded. */
    private fun suggestions(ctx: Context, active: JSONArray): JSONArray {
        val hist = Data.readArray(ctx, HISTORY)
        data class Group(val title: String, val icon: String, var count: Int)
        val groups = ArrayList<Group>()
        for (i in 0 until hist.length()) {
            val h = hist.getJSONObject(i)
            val t = h.optString("title")
            if (t.isEmpty()) continue
            val g = groups.find { MagicWords.fuzzyEquals(it.title.lowercase(), t.lowercase()) }
            if (g != null) g.count++
            else groups.add(Group(t, h.optString("icon").ifEmpty { "⭐" }, 1))
        }
        val activeTitles = (0 until active.length()).map {
            active.getJSONObject(it).optString("title").lowercase()
        }
        val out = JSONArray()
        groups.filter { g -> g.count >= 2 &&
                activeTitles.none { MagicWords.fuzzyEquals(it, g.title.lowercase()) } }
            .sortedByDescending { it.count }
            .take(8)
            .forEach { out.put(JSONObject().put("title", it.title).put("icon", it.icon)) }
        return out
    }

    fun mutate(ctx: Context, action: JSONObject): String {
        when (action.getString("action")) {
            "addChore" -> {
                val title = action.getString("title").trim()
                if (title.isEmpty()) throw IllegalArgumentException("the chore needs a name")
                // One chore per assignee — "memberIds" assigns several kids at once.
                val ids = action.optJSONArray("memberIds")
                val targets = if (ids != null && ids.length() > 0)
                    (0 until ids.length()).map { ids.optString(it) }
                else listOf(action.optString("memberId"))
                Data.mutate(ctx, FILE) { arr ->
                    for (mid in targets) {
                        val chore = JSONObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("title", title)
                            .put("memberId", mid)
                            .put("icon", action.optString("icon").ifEmpty { "⭐" })
                        if (action.optBoolean("oneTime", false)) {
                            chore.put("oneTime", true)
                            chore.put("date", action.optString("date").ifEmpty { dayFmt().format(Date()) })
                        } else {
                            chore.put("days", action.optJSONArray("days")
                                ?: JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
                        }
                        arr.put(chore)
                    }
                }
                // Remember what gets added — the composer learns the family's
                // common chores and offers them as personalized quick-picks.
                Data.mutate(ctx, HISTORY) { hist ->
                    hist.put(JSONObject()
                        .put("title", title)
                        .put("icon", action.optString("icon").ifEmpty { "⭐" }))
                    while (hist.length() > 200) hist.remove(0)
                }
            }
            "deleteChore" -> {
                val id = action.getString("choreId")
                Data.mutate(ctx, FILE) { arr ->
                    for (i in arr.length() - 1 downTo 0)
                        if (arr.getJSONObject(i).optString("id") == id) arr.remove(i)
                }
                // Drop only completions that can't stand on their own (no
                // stamped memberId) — earned stars survive the deletion.
                Data.mutate(ctx, DONE) { done ->
                    for (i in done.length() - 1 downTo 0) {
                        val d = done.getJSONObject(i)
                        if (d.optString("choreId") == id && d.optString("memberId").isEmpty())
                            done.remove(i)
                    }
                }
            }
            "toggle" -> {
                val id = action.getString("choreId")
                val today = dayFmt().format(Date())
                // Resolve the assignee now so the star outlives the chore.
                val memberId = Data.readArray(ctx, FILE).let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("id") == id }
                        ?.optString("memberId").orEmpty()
                }
                Data.mutate(ctx, DONE) { done ->
                    var removed = false
                    for (i in done.length() - 1 downTo 0) {
                        val d = done.getJSONObject(i)
                        if (d.optString("choreId") == id && d.optString("date") == today) {
                            done.remove(i); removed = true
                        }
                    }
                    if (!removed) done.put(JSONObject()
                        .put("choreId", id).put("date", today).put("memberId", memberId))
                    prune(done)
                }
            }
            "setGoal" -> {
                val mid = action.getString("memberId")
                val goal = action.getInt("goal").coerceIn(1, 99)
                Data.mutate(ctx, GOALS) { arr ->
                    var found = false
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optString("memberId") == mid) { o.put("goal", goal); found = true }
                    }
                    if (!found) arr.put(JSONObject().put("memberId", mid).put("goal", goal))
                }
            }
            else -> throw IllegalArgumentException("unknown action")
        }
        App.instance.notifyDataChanged()
        return statusJson(ctx)
    }

    /** Due on a given yyyy-MM-dd date (+ its Calendar.DAY_OF_WEEK). */
    fun isDueOn(chore: JSONObject, date: String, dayOfWeek: Int): Boolean {
        if (chore.optBoolean("oneTime")) return chore.optString("date") == date
        val days = chore.optJSONArray("days") ?: return true
        for (i in 0 until days.length()) if (days.optInt(i) == dayOfWeek) return true
        return false
    }

    private fun prune(done: JSONArray) {
        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -60) }
        val cut = dayFmt().format(cutoff.time)
        for (i in done.length() - 1 downTo 0)
            if (done.getJSONObject(i).optString("date") < cut) done.remove(i)
    }

    private fun weekStartStr(ctx: Context): String {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val wkStart = ConfigStore(ctx).weekStartResolved()
        while (c.get(Calendar.DAY_OF_WEEK) != wkStart) c.add(Calendar.DAY_OF_MONTH, -1)
        return dayFmt().format(c.time)
    }
}
