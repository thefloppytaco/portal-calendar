package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Deterministic, reviewable reward calculation; it never changes device limits by itself. */
object Rewards {
    private val fmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun preview(ctx: Context, memberId: String): String {
        require(memberId.isNotBlank()) { "memberId is required" }
        val choreHistory = JSONObject(Chores.historyJson(ctx))
        val routineHistory = JSONObject(Routines.historyJson(ctx))
        val chores = choreHistory.getJSONArray("chores")
        val choreDone = choreHistory.getJSONArray("completed")
        val routines = routineHistory.getJSONArray("items")
        val routineDone = routineHistory.getJSONArray("completed")
        var streak = 0
        val days = JSONArray()
        for (offset in 1..30) {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -offset) }
            val date = fmt.format(cal.time)
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val dueChores = (0 until chores.length()).map { chores.getJSONObject(it) }
                .filter { it.optString("memberId") == memberId && Chores.isDueOn(it, date, dow) }
            val dueRoutines = (0 until routines.length()).map { routines.getJSONObject(it) }
                .filter { it.optString("memberId") == memberId && Routines.isDueOn(it, date, dow) }
            val choreOk = dueChores.all { c -> hasDone(choreDone, "choreId", c.optString("id"), date) }
            val routineOk = dueRoutines.all { r -> hasDone(routineDone, "itemId", r.optString("id"), date) }
            val complete = (dueChores.isNotEmpty() || dueRoutines.isNotEmpty()) && choreOk && routineOk
            if (complete) streak++ else break
            days.put(JSONObject().put("date", date).put("complete", complete)
                .put("chores", dueChores.size).put("routines", dueRoutines.size))
        }
        val bonus = when {
            streak >= 30 -> 10
            streak >= 7 -> 3
            streak >= 3 -> 2
            streak >= 1 -> 1
            else -> 0
        }
        return JSONObject().put("memberId", memberId).put("streakDays", streak)
            .put("bonusHours", bonus).put("days", days).toString()
    }

    /** Gotify-ready approval copy with links Juanita can tap from her phone. */
    fun approvalMessage(ctx: Context, memberId: String, portalUrl: String): String {
        val preview = JSONObject(preview(ctx, memberId))
        val name = Members.byId(ctx, memberId)?.name ?: "Kaylee"
        val hours = preview.optInt("bonusHours")
        val streak = preview.optInt("streakDays")
        val approve = portalUrl.trimEnd('/') + "/?approveMember=" + java.net.URLEncoder.encode(memberId, "UTF-8")
        val familyLink = "https://families.google.com/"
        return "${name} has completed her morning routine!\n" +
            "Recommendation: add ${hours} hour${if (hours == 1) "" else "s"} of bonus time " +
            "(${streak}-day streak).\n\n" +
            "Approve in PortalHub: $approve\n" +
            "Open Family Link: $familyLink"
    }

    /**
     * Send at most one parent-review notification per member and day. This is
     * intentionally only a recommendation: Family Link remains the authority
     * for changing device time, and a parent must approve it.
     */
    fun notifyEligible(ctx: Context, portalUrl: String): Int {
        if (!Gotify.configured(ctx)) return 0
        val day = fmt.format(Calendar.getInstance().time)
        val prefs = ctx.getSharedPreferences("reward-notices", Context.MODE_PRIVATE)
        var sent = 0
        Members.all(ctx).forEach { member ->
            val key = "$day|${member.id}"
            if (prefs.getBoolean(key, false)) return@forEach
            val preview = JSONObject(preview(ctx, member.id))
            if (preview.optInt("bonusHours") <= 0) return@forEach
            if (Gotify.send(ctx, "FamilyHub approval", approvalMessage(ctx, member.id, portalUrl), 7)) {
                prefs.edit().putBoolean(key, true).apply()
                sent++
            }
        }
        return sent
    }

    private fun hasDone(done: JSONArray, key: String, id: String, date: String): Boolean =
        (0 until done.length()).any {
            val d = done.getJSONObject(it)
            d.optString(key) == id && d.optString("date") == date
        }
}
