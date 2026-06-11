package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Magic word" calendar events: title an event in any synced calendar with a
 * trigger prefix and the board hides it from the calendar and routes it:
 *
 *   todo: pick up dry cleaning      → "To-Do" list
 *   list: new shin guards           → "To-Do" list
 *   groceries: oat milk             → "Groceries" list
 *   chore: chani water the plants   → one-time chore for Chani on the
 *                                     event's date (name token optional)
 *
 * Each event UID is processed exactly once, so feed re-syncs don't duplicate.
 * The event itself stays in the source calendar (a handy log of what was
 * sent), it just never renders on the board.
 */
object MagicWords {
    private const val DONE_FILE = "magic_done.json"
    private val TRIGGER = Regex("^(todo|list|grocery|groceries|chore)\\s*[:：]\\s*(.+)$",
        RegexOption.IGNORE_CASE)

    data class Directive(val kind: String, val payload: String)

    fun match(title: String): Directive? {
        val m = TRIGGER.find(title.trim()) ?: return null
        val kind = when (m.groupValues[1].lowercase(Locale.US)) {
            "grocery", "groceries" -> "groceries"
            "chore" -> "chore"
            else -> "todo"
        }
        return Directive(kind, m.groupValues[2].trim())
    }

    /** True the first time a UID is seen; remembers it (capped log). */
    @Synchronized
    fun markProcessed(ctx: Context, uid: String): Boolean {
        val arr = Data.readArray(ctx, DONE_FILE)
        for (i in 0 until arr.length()) if (arr.optString(i) == uid) return false
        arr.put(uid)
        while (arr.length() > 500) arr.remove(0)
        Data.writeArray(ctx, DONE_FILE, arr)
        return true
    }

    fun execute(ctx: Context, d: Directive, eventStartMillis: Long) {
        when (d.kind) {
            "todo" -> addToList(ctx, "To-Do", d.payload)
            "groceries" -> addToList(ctx, "Groceries", d.payload)
            "chore" -> {
                var title = d.payload
                var memberId = ""
                // A member's name anywhere in the text assigns the chore
                // (and is dropped from the title to avoid "make Chani Chani's bed").
                for (m in Members.all(ctx)) {
                    val rx = Regex("(^|\\s)${Regex.escape(m.name)}('s)?(\\s|$)", RegexOption.IGNORE_CASE)
                    if (rx.containsMatchIn(title)) {
                        memberId = m.id
                        title = title.replace(rx, " ").trim().replace(Regex("\\s+"), " ")
                        break
                    }
                }
                if (title.isEmpty()) title = d.payload
                Chores.mutate(ctx, JSONObject()
                    .put("action", "addChore")
                    .put("title", title.replaceFirstChar { it.uppercase() })
                    .put("memberId", memberId)
                    .put("icon", "⭐")
                    .put("oneTime", true)
                    .put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .format(Date(eventStartMillis))))
            }
        }
    }

    private fun addToList(ctx: Context, listName: String, item: String) {
        val lists = JSONArray(FamilyLists.json(ctx))
        var listId: String? = null
        for (i in 0 until lists.length()) {
            val l = lists.getJSONObject(i)
            if (l.optString("name").equals(listName, true)) {
                listId = l.optString("id"); break
            }
        }
        if (listId == null) {
            val created = JSONArray(FamilyLists.mutate(ctx,
                JSONObject().put("action", "addList").put("name", listName)))
            for (i in 0 until created.length()) {
                val l = created.getJSONObject(i)
                if (l.optString("name").equals(listName, true)) listId = l.optString("id")
            }
        }
        listId?.let {
            FamilyLists.mutate(ctx, JSONObject()
                .put("action", "addItem").put("listId", it)
                .put("text", item.replaceFirstChar { c -> c.uppercase() }))
        }
    }
}
