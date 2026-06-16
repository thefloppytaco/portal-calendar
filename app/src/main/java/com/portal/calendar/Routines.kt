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
 * Kids' get-ready checklists ("routines") — the same shape as [Chores] but
 * without the star economy: a routine item is a thing to tick off as part of a
 * daily routine (pack homework, brush teeth, lunchbox in the bag), grouped by a
 * time-of-day [section] (morning / afternoon / evening / anytime) so a column
 * reads like a real morning list. An item belongs to a member ("" = anyone) and
 * repeats on chosen weekdays (1=Sun … 7=Sat) or is a one-time reminder.
 *
 * Completions are an append-only {itemId, date} log; toggling today adds/removes
 * the entry, and everything resets each morning. There are no points and no
 * goals — finishing the list IS the reward — so this stays deliberately simpler
 * than Chores.
 *
 * Off by default (see [ConfigStore.featureEnabled]); the tab only appears once a
 * family switches Routines on, keeping the board uncluttered for those who don't
 * want it.
 */
object Routines {
    private const val FILE = "routines.json"
    private const val DONE = "routine_done.json"

    /** Display order + default icon for the time-of-day sections. */
    val SECTIONS = listOf(
        "morning" to "☀️", "afternoon" to "🌤️", "evening" to "🌙", "anytime" to "🔁")

    private fun dayFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** [includePins] only for the board's own PIN pad — never over HTTP. */
    fun statusJson(ctx: Context, includePins: Boolean = false): String {
        var items = Data.readArray(ctx, FILE)
        // One-time items quietly retire a few days after their date (same as chores).
        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -3) }
        val cut = dayFmt().format(cutoff.time)
        val needsPrune = (0 until items.length()).any {
            val c = items.getJSONObject(it)
            c.optBoolean("oneTime") && c.optString("date") < cut
        }
        if (needsPrune) items = Data.mutate(ctx, FILE) { arr ->
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
            if (d.optString("date") == today) doneToday.put(d.optString("itemId"))
        }

        return JSONObject()
            .put("items", items)
            .put("doneToday", doneToday)
            .put("sections", JSONArray(SECTIONS.map { it.first }))
            .put("members", JSONArray(
                if (includePins) Members.json(ctx) else Members.publicJson(ctx)))
            .toString()
    }

    fun mutate(ctx: Context, action: JSONObject): String {
        when (action.getString("action")) {
            "addItem" -> {
                val title = action.getString("title").trim()
                if (title.isEmpty()) throw IllegalArgumentException("the item needs a name")
                // One item per assignee — "memberIds" assigns several kids at once.
                val ids = action.optJSONArray("memberIds")
                val targets = if (ids != null && ids.length() > 0)
                    (0 until ids.length()).map { ids.optString(it) }
                else listOf(action.optString("memberId"))
                val section = action.optString("section")
                    .takeIf { s -> SECTIONS.any { it.first == s } } ?: "morning"
                Data.mutate(ctx, FILE) { arr ->
                    for (mid in targets) {
                        val item = JSONObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("title", title)
                            .put("memberId", mid)
                            .put("section", section)
                            .put("icon", action.optString("icon").ifEmpty { "✅" })
                        if (action.optBoolean("oneTime", false)) {
                            item.put("oneTime", true)
                            item.put("date", action.optString("date").ifEmpty { dayFmt().format(Date()) })
                        } else {
                            item.put("days", action.optJSONArray("days")
                                ?: JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
                        }
                        arr.put(item)
                    }
                }
            }
            "deleteItem" -> {
                val id = action.getString("itemId")
                Data.mutate(ctx, FILE) { arr ->
                    for (i in arr.length() - 1 downTo 0)
                        if (arr.getJSONObject(i).optString("id") == id) arr.remove(i)
                }
                Data.mutate(ctx, DONE) { done ->
                    for (i in done.length() - 1 downTo 0)
                        if (done.getJSONObject(i).optString("itemId") == id) done.remove(i)
                }
            }
            "toggle" -> {
                val id = action.getString("itemId")
                val today = dayFmt().format(Date())
                Data.mutate(ctx, DONE) { done ->
                    var removed = false
                    for (i in done.length() - 1 downTo 0) {
                        val d = done.getJSONObject(i)
                        if (d.optString("itemId") == id && d.optString("date") == today) {
                            done.remove(i); removed = true
                        }
                    }
                    if (!removed) done.put(JSONObject().put("itemId", id).put("date", today))
                    prune(done)
                }
            }
            else -> throw IllegalArgumentException("unknown action")
        }
        FamilySync.pushIfSpoke(ctx, "routines", action.toString())
        App.instance.notifyDataChanged()
        return statusJson(ctx)
    }

    /** Due on a given yyyy-MM-dd date (+ its Calendar.DAY_OF_WEEK). */
    fun isDueOn(item: JSONObject, date: String, dayOfWeek: Int): Boolean {
        if (item.optBoolean("oneTime")) return item.optString("date") == date
        val days = item.optJSONArray("days") ?: return true
        for (i in 0 until days.length()) if (days.optInt(i) == dayOfWeek) return true
        return false
    }

    private fun prune(done: JSONArray) {
        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -14) }
        val cut = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cutoff.time)
        for (i in done.length() - 1 downTo 0)
            if (done.getJSONObject(i).optString("date") < cut) done.remove(i)
    }
}
