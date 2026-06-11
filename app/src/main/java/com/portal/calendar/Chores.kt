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
 * append-only log of {choreId, date}; toggling today removes/adds the entry.
 * Stars = completions this week, compared against a per-member weekly goal.
 */
object Chores {
    private const val FILE = "chores.json"
    private const val DONE = "chore_done.json"
    private const val GOALS = "star_goals.json"
    const val DEFAULT_GOAL = 10

    private fun dayFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun statusJson(ctx: Context): String {
        val chores = Data.readArray(ctx, FILE)
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
        val weekStart = weekStartStr()
        val stars = JSONObject()
        for (i in 0 until done.length()) {
            val d = done.getJSONObject(i)
            if (d.optString("date") >= weekStart) {
                val m = choreMember[d.optString("choreId")] ?: continue
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
            .put("members", JSONArray(Members.json(ctx)))
            .toString()
    }

    fun mutate(ctx: Context, action: JSONObject): String {
        when (action.getString("action")) {
            "addChore" -> {
                val title = action.getString("title").trim()
                if (title.isEmpty()) throw IllegalArgumentException("the chore needs a name")
                val arr = Data.readArray(ctx, FILE)
                arr.put(JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("title", title)
                    .put("memberId", action.optString("memberId"))
                    .put("icon", action.optString("icon").ifEmpty { "⭐" })
                    .put("days", action.optJSONArray("days") ?: JSONArray(listOf(1, 2, 3, 4, 5, 6, 7))))
                Data.writeArray(ctx, FILE, arr)
            }
            "deleteChore" -> {
                val arr = Data.readArray(ctx, FILE)
                val id = action.getString("choreId")
                for (i in arr.length() - 1 downTo 0)
                    if (arr.getJSONObject(i).optString("id") == id) arr.remove(i)
                Data.writeArray(ctx, FILE, arr)
                // Purge its completions so deleted chores never haunt the log.
                val done = Data.readArray(ctx, DONE)
                for (i in done.length() - 1 downTo 0)
                    if (done.getJSONObject(i).optString("choreId") == id) done.remove(i)
                Data.writeArray(ctx, DONE, done)
            }
            "toggle" -> {
                val id = action.getString("choreId")
                val today = dayFmt().format(Date())
                val done = Data.readArray(ctx, DONE)
                var removed = false
                for (i in done.length() - 1 downTo 0) {
                    val d = done.getJSONObject(i)
                    if (d.optString("choreId") == id && d.optString("date") == today) {
                        done.remove(i); removed = true
                    }
                }
                if (!removed) done.put(JSONObject().put("choreId", id).put("date", today))
                prune(done)
                Data.writeArray(ctx, DONE, done)
            }
            "setGoal" -> {
                val arr = Data.readArray(ctx, GOALS)
                val mid = action.getString("memberId")
                val goal = action.getInt("goal").coerceIn(1, 99)
                var found = false
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("memberId") == mid) { o.put("goal", goal); found = true }
                }
                if (!found) arr.put(JSONObject().put("memberId", mid).put("goal", goal))
                Data.writeArray(ctx, GOALS, arr)
            }
            else -> throw IllegalArgumentException("unknown action")
        }
        App.instance.notifyDataChanged()
        return statusJson(ctx)
    }

    /** Chores due on the given Calendar.DAY_OF_WEEK. */
    fun isDue(chore: JSONObject, dayOfWeek: Int): Boolean {
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

    private fun weekStartStr(): String {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        while (c.get(Calendar.DAY_OF_WEEK) != c.firstDayOfWeek) c.add(Calendar.DAY_OF_MONTH, -1)
        return dayFmt().format(c.time)
    }
}
