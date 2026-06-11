package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Calendar-event commands, two strictness levels:
 *
 * Prefix triggers (work in ANY synced calendar, typo-tolerant):
 *   todo: / list: / list groceries: / groceries: / chore:
 *
 * Loose natural language (only in feeds marked as an INBOX calendar, where
 * every event is a command and nothing renders):
 *   "add oat milk to groceries"      → that list (fuzzy-matched, auto-created)
 *   "buy oat milk" / "pick up …"     → Groceries
 *   "remind Chani to water plants"   → one-time chore for Chani on the date
 *   "Chani needs to water plants"    → same
 *   anything else with a kid's name  → chore; otherwise → To-Do
 *
 * List names, trigger words and member names all use small-edit-distance
 * fuzzy matching, so "groceres: oatt milkk" still lands in Groceries (the
 * item text itself stays as typed). Each event UID processes exactly once.
 */
object MagicWords {
    private const val DONE_FILE = "magic_done.json"

    data class Directive(
        val kind: String, // todo | groceries | chore | list
        val payload: String,
        val listName: String? = null,
        val memberId: String = "")

    // ------------------------------------------------------------ matching

    /** Strict prefix form. Short triggers must be exact; long ones tolerate typos. */
    fun match(title: String): Directive? {
        val m = Regex("^([\\p{L}]+)(?:\\s+([^:：]+?))?\\s*[:：]\\s*(.+)$")
            .find(title.trim()) ?: return null
        val key = m.groupValues[1].lowercase(Locale.US)
        val qualifier = m.groupValues[2].trim().ifEmpty { null }
        val payload = m.groupValues[3].trim()
        return when {
            key == "todo" || key == "task" -> Directive("todo", payload)
            fuzzyEquals(key, "grocery") || fuzzyEquals(key, "groceries") ->
                Directive("groceries", payload)
            key == "chore" || key == "chores" -> Directive("chore", payload)
            key == "list" ->
                if (qualifier != null) Directive("list", payload, listName = qualifier)
                else Directive("todo", payload)
            else -> null
        }
    }

    /** Loose parsing for inbox calendars — always returns something sensible. */
    fun parseLoose(ctx: Context, title: String): Directive {
        match(title)?.let { return it }
        val t = title.trim()
        val lower = t.lowercase(Locale.US)

        Regex("^(?:add|put)\\s+(.+?)\\s+(?:to|on)\\s+(?:the\\s+)?(.+?)(?:\\s+list)?$")
            .find(lower)?.let {
                return Directive("list", it.groupValues[1], listName = it.groupValues[2])
            }
        Regex("^(?:buy|get|grab|pick\\s+up)\\s+(.+)$").find(lower)?.let {
            return Directive("groceries", it.groupValues[1])
        }
        Regex("^(?:remind|tell|ask)\\s+([\\p{L}]+)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(t)?.let { m ->
            findMember(ctx, m.groupValues[1])?.let { member ->
                return Directive("chore", m.groupValues[2], memberId = member.id)
            }
        }
        Regex("^([\\p{L}]+)\\s+(?:needs\\s+to|should|has\\s+to|must)\\s+(.+)$",
            RegexOption.IGNORE_CASE).find(t)?.let { m ->
            findMember(ctx, m.groupValues[1])?.let { member ->
                return Directive("chore", m.groupValues[2], memberId = member.id)
            }
        }
        // A family member's name anywhere → it's a chore; otherwise a to-do.
        for (token in t.split(Regex("\\s+"))) {
            findMember(ctx, token.removeSuffix("'s"))?.let {
                return Directive("chore", t)
            }
        }
        return Directive("todo", t)
    }

    // ----------------------------------------------------------- execution

    fun execute(ctx: Context, d: Directive, eventStartMillis: Long) {
        when (d.kind) {
            "todo" -> addToList(ctx, "To-Do", d.payload)
            "groceries" -> addToList(ctx, "Groceries", d.payload)
            "list" -> addToList(ctx, d.listName ?: "To-Do", d.payload)
            "chore" -> {
                var title = d.payload
                var memberId = d.memberId
                if (memberId.isEmpty()) {
                    for (m in Members.all(ctx)) {
                        val hit = title.split(Regex("\\s+")).firstOrNull {
                            fuzzyEquals(it.removeSuffix("'s").lowercase(Locale.US),
                                m.name.lowercase(Locale.US))
                        }
                        if (hit != null) {
                            memberId = m.id
                            title = title.split(Regex("\\s+"))
                                .filter { it != hit }.joinToString(" ").trim()
                            break
                        }
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

    /** Fuzzy find-or-create, so "groceres" can't spawn a duplicate list. */
    private fun addToList(ctx: Context, requestedName: String, item: String) {
        val lists = JSONArray(FamilyLists.json(ctx))
        var listId: String? = null
        for (i in 0 until lists.length()) {
            val l = lists.getJSONObject(i)
            if (fuzzyEquals(l.optString("name").lowercase(Locale.US),
                    requestedName.lowercase(Locale.US))) {
                listId = l.optString("id"); break
            }
        }
        if (listId == null) {
            val pretty = requestedName.trim().replaceFirstChar { it.uppercase() }
            val created = JSONArray(FamilyLists.mutate(ctx,
                JSONObject().put("action", "addList").put("name", pretty)))
            for (i in 0 until created.length()) {
                val l = created.getJSONObject(i)
                if (l.optString("name") == pretty) listId = l.optString("id")
            }
        }
        listId?.let {
            FamilyLists.mutate(ctx, JSONObject()
                .put("action", "addItem").put("listId", it)
                .put("text", item.trim().replaceFirstChar { c -> c.uppercase() }))
        }
    }

    private fun findMember(ctx: Context, token: String): Member? =
        Members.all(ctx).firstOrNull {
            fuzzyEquals(token.lowercase(Locale.US), it.name.lowercase(Locale.US))
        }

    // ------------------------------------------------------------ plumbing

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

    /** Singular/plural-blind, small-edit-distance equality. */
    fun fuzzyEquals(a: String, b: String): Boolean {
        if (a == b) return true
        val x = a.removeSuffix("s")
        val y = b.removeSuffix("s")
        if (x == y) return true
        val maxLen = maxOf(x.length, y.length)
        val allowed = when {
            maxLen <= 3 -> 0
            maxLen <= 6 -> 1
            else -> 2
        }
        return allowed > 0 && levenshtein(x, y) <= allowed
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
